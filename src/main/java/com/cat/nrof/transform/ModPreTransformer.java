package com.cat.nrof.transform;

import com.cat.nrof.Nrof;
import com.cat.nrof.cache.CachedNeoForgeMod;
import com.cat.nrof.convert.NrofModMetadata;
import com.cat.nrof.discovery.DiscoveredNeoForgeMod;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Pre-builds the converted mod before Fabric loads it. Fabric resolves mods
 * lazily, so the transformed code must be materialized ahead of time and cached
 * on disk ({@code classes/} inside the cache entry).
 *
 * <p>Phase 1 (this class) runs an identity pre-build: it materializes the
 * recognized mixin config resources into the cache so the Fabric mixin pipeline
 * can consume them via the generated metadata. Bytecode-level pre-application
 * (mixins/ATs into cached {@code .class} files) plugs in here in a later phase.</p>
 */
public final class ModPreTransformer {

    public static void preBuild(DiscoveredNeoForgeMod mod, CachedNeoForgeMod cached,
                                List<String> forwardedMixinConfigs) {
        Path classes = cached.classesDir();
        try {
            Files.createDirectories(classes);

            StringBuilder report = new StringBuilder();
            report.append("# NROF pre-build report\n");
            report.append("source=").append(mod.originPath()).append('\n');
            report.append("mixin-configs-forwarded=").append(forwardedMixinConfigs.size()).append('\n');
            for (String cfg : forwardedMixinConfigs) {
                report.append("  ").append(cfg).append('\n');
            }
            Files.writeString(classes.resolve("prebuild.log"), report, StandardCharsets.UTF_8);

            // Materialize each forwarded mixin config into the cache entry so the
            // loader can resolve it relative to the cached mod.
            try (java.util.zip.ZipFile zip = new java.util.zip.ZipFile(mod.originPath().toFile())) {
                for (String cfg : forwardedMixinConfigs) {
                    var entry = zip.getEntry(cfg);
                    if (entry == null) {
                        Nrof.LOGGER.warn("[NROF] mixin config {} declared but missing in {}", cfg, mod.originPath());
                        continue;
                    }
                    Path target = classes.resolve(normalize(cfg));
                    Files.createDirectories(target.getParent());
                    try (java.io.InputStream in = zip.getInputStream(entry)) {
                        Files.copy(in, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                    }
                }
            }

            // Remap mojmap -> official -> intermediary so the mod's Minecraft
            // references resolve against Fabric's runtime namespace.
            Path remapped = cached.entryDir().resolve("remapped.jar");
            ModRemapper.remapToIntermediary(mod.originPath(), remapped);

            // Extract + remap nested JiJ jars (META-INF/jars/*, e.g. Registrate,
            // Ponder, Flywheel inside create.jar). The main jar's classes link
            // against them; without this step @Mod class loading fails with
            // NoClassDefFoundError on the embedded libraries.
            processNestedJars(mod.originPath(), cached.entryDir());
        } catch (IOException e) {
            Nrof.LOGGER.error("[NROF] pre-build failed for {}", mod.originPath(), e);
        }
    }

    /** Directory inside a cache entry holding extracted (remapped) nested jars. */
    public static Path nestedJarsDir(Path entryDir) {
        return entryDir.resolve("nested-jars");
    }

    /**
     * Recursively pulls nested JiJ jars out of {@code jar} into
     * {@code <entryDir>/nested-jars/} and remaps each one to intermediary.
     * Idempotent: already-extracted jars are reused so the cache-hit path
     * stays cheap.
     */
    private static void processNestedJars(Path jar, Path entryDir) throws IOException {
        extractNested(jar, nestedJarsDir(entryDir), "", 0);
    }

    private static void extractNested(Path jar, Path outDir, String prefix, int depth) throws IOException {
        if (depth > 2) {
            return; // nesting deeper than 2 levels is not a thing in practice
        }
        try (java.util.zip.ZipFile zip = new java.util.zip.ZipFile(jar.toFile())) {
            List<String> names = new ArrayList<>();
            for (java.util.Enumeration<? extends java.util.zip.ZipEntry> it = zip.entries(); it.hasMoreElements(); ) {
                java.util.zip.ZipEntry e = it.nextElement();
                String n = e.getName();
                // both nesting flavors: Fabric JiJ (META-INF/jars/) and
                // NeoForge JarJar (META-INF/jarjar/, real jars + metadata.json)
                boolean nestedJar = n.endsWith(".jar")
                        && (n.startsWith("META-INF/jars/") || n.startsWith("META-INF/jarjar/"));
                if (!e.isDirectory() && nestedJar) {
                    names.add(n);
                }
            }
            for (String name : names) {
                String base = prefix + name.substring(name.lastIndexOf('/') + 1);
                Path raw = outDir.resolve(base);
                Files.createDirectories(raw.getParent());
                if (!Files.exists(raw)) {
                    try (java.io.InputStream in = zip.getInputStream(zip.getEntry(name))) {
                        Files.copy(in, raw, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                    }
                }
                Path remapped = outDir.resolve(base.substring(0, base.length() - ".jar".length()) + "-remapped.jar");
                if (!Files.exists(remapped)) {
                    ModRemapper.remapToIntermediary(raw, remapped);
                }
                // nested jars may embed their own JiJ deps — follow one level down
                extractNested(raw, outDir, base.substring(0, base.length() - ".jar".length()) + "--", depth + 1);
            }
        } catch (java.util.zip.ZipException e) {
            // a broken nested jar should not take down the whole conversion
            Nrof.LOGGER.warn("[NROF] skipping unreadable nested jar {}: {}", jar.getFileName(), e.toString());
        }
    }

    private static String normalize(String resource) {
        return resource.replace('/', '_').replace('\\', '_');
    }

    private ModPreTransformer() {
    }
}
