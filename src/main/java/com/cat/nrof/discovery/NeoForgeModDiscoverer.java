package com.cat.nrof.discovery;

import com.cat.nrof.Nrof;
import com.cat.nrof.NrofVersion;
import com.cat.nrof.model.NeoForgeModInfo;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Scans the Fabric mods directory for NeoForge mod jars, i.e. jars containing
 * {@code META-INF/neoforge.mods.toml}, and parses their metadata.
 */
public final class NeoForgeModDiscoverer {

    public static final String NEOFORGE_MODS_TOML = "META-INF/neoforge.mods.toml";

    public static List<DiscoveredNeoForgeMod> discover(Path modsDir) {
        List<DiscoveredNeoForgeMod> out = new ArrayList<>();
        if (!Files.isDirectory(modsDir)) {
            return out;
        }
        try (Stream<Path> files = Files.list(modsDir)) {
            for (Path p : files.toList()) {
                if (!isJar(p)) {
                    continue;
                }
                DiscoveredNeoForgeMod mod = tryDiscover(p);
                if (mod != null) {
                    out.add(mod);
                }
            }
        } catch (IOException e) {
            Nrof.LOGGER.error("[NROF] failed to scan mods directory {}", modsDir, e);
        }
        return out;
    }

    private static boolean isJar(Path p) throws IOException {
        String name = p.getFileName().toString();
        return Files.isRegularFile(p)
                && (name.endsWith(".jar") || name.endsWith(".zip"))
                && !name.endsWith("-sources.jar");
    }

    private static DiscoveredNeoForgeMod tryDiscover(Path jar) {
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            ZipEntry toml = zip.getEntry(NEOFORGE_MODS_TOML);
            if (toml == null) {
                return null;
            }
            List<NeoForgeModInfo> infos;
            try (InputStream in = zip.getInputStream(toml)) {
                infos = ModsTomlParser.parse(in);
            } catch (IOException e) {
                Nrof.LOGGER.error("[NROF] failed to parse {} in {}", NEOFORGE_MODS_TOML, jar, e);
                return null;
            }
            if (isFfapiDistribution(infos)) {
                // FFAPI is built into this environment: every FFAPI class reference
                // resolves against the real Fabric API already on the classpath.
                // A separately installed FFAPI jar would shadow those classes, so
                // it is deliberately excluded from conversion and loading.
                Nrof.LOGGER.info("[NROF] skipping externally installed Forgified Fabric API jar {} — FFAPI is built in (backed by the real Fabric API)",
                        jar.getFileName());
                return null;
            }
            // Jar-level mixin configs: [[mixins]] entries plus any *.mixins.json present.
            List<String> mixinConfigs = new ArrayList<>(infos.get(0).mixinConfigs());
            for (NeoForgeModInfo info : infos.subList(1, infos.size())) {
                for (String cfg : info.mixinConfigs()) {
                    if (!mixinConfigs.contains(cfg)) {
                        mixinConfigs.add(cfg);
                    }
                }
            }
            for (java.util.Enumeration<? extends ZipEntry> e = zip.entries(); e.hasMoreElements(); ) {
                String n = e.nextElement().getName();
                if (n.endsWith(".mixins.json") && !mixinConfigs.contains(n)) {
                    mixinConfigs.add(n);
                }
            }
            return new DiscoveredNeoForgeMod(jar, contentKey(jar), infos, mixinConfigs);
        } catch (IOException | UncheckedIOException e) {
            Nrof.LOGGER.error("[NROF] failed to read jar {}", jar, e);
            return null;
        }
    }

    /**
     * Stable cache key: content identity of the source jar (path + size + mtime)
     * mixed with the cache format version so any layout change invalidates.
     */
    static String contentKey(Path jar) throws IOException {
        String identity = jar.toAbsolutePath().toString()
                + "|" + Files.size(jar)
                + "|" + Files.getLastModifiedTime(jar).toMillis()
                + "|" + NrofVersion.CACHE_FORMAT;
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(identity.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    /**
     * A Forgified Fabric API distribution jar contains only framework modules
     * (the {@code fabric_api} umbrella plus its {@code fabric_*} sub-modules).
     * Detected by: every mod entry in the toml is a framework module id, and
     * at least one of them is an umbrella id.
     */
    private static boolean isFfapiDistribution(List<NeoForgeModInfo> infos) {
        if (infos.isEmpty()) {
            return false;
        }
        boolean hasUmbrella = false;
        for (NeoForgeModInfo info : infos) {
            String id = info.modId();
            if (com.cat.nrof.convert.FfapiIds.isFfapiUmbrella(id)) {
                hasUmbrella = true;
                continue;
            }
            if (!com.cat.nrof.convert.FfapiIds.isFfapiModule(id)) {
                return false;
            }
        }
        return hasUmbrella;
    }

    private NeoForgeModDiscoverer() {
    }
}
