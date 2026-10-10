package com.cat.nrof.loader;

import com.cat.nrof.Nrof;
import com.cat.nrof.cache.CachedNeoForgeMod;
import com.cat.nrof.runtime.NrofModRuntime;
import com.cat.nrof.runtime.NrofRuntime;
import net.fabricmc.loader.impl.FabricLoaderImpl;
import net.fabricmc.loader.impl.metadata.DependencyOverrides;
import net.fabricmc.loader.impl.metadata.LoaderModMetadata;
import net.fabricmc.loader.impl.metadata.ModMetadataParser;
import net.fabricmc.loader.impl.metadata.VersionOverrides;

import java.io.InputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Loader-side hook invoked from {@code MainMixin} (head of
 * {@code net.minecraft.client.main.Main#main}).
 *
 * <p>Runs the NROF pipeline (discover → bus → convert → disk cache) and registers
 * every cached conversion as a native {@code ModContainerImpl} inside
 * {@link FabricLoaderImpl}'s mod index, so Fabric APIs
 * ({@code FabricLoader.getAllMods/isModLoaded/getModContainer}, mod menus, ...)
 * recognize the converted NeoForge mods.</p>
 *
 * <p>{@code FabricLoaderImpl} and {@code ModCandidateImpl} live in the loader's
 * core package (app classloader) where mod mixins cannot reach, so registration
 * goes through reflection. The hook itself is only referenced from the knot-realm
 * {@code MainMixin}, which is loadable.</p>
 */
public final class NrofLoaderHook {

    private static final AtomicBoolean REGISTERED = new AtomicBoolean(false);
    /** Converted, cached, mounted, and index-registered mods — populated in the
     *  very early Main#main HEAD pass so Fabric entrypoints see the mods in the
     *  loader index. Their @Mod-constructors + lifecycle events MUST NOT run yet
     *  because registries are not bootstrapped (calling
     *  RegistryKey.register / BuiltInRegistries at this point throws
     *  "Not bootstrapped" from RegistryManager). Lifecycle is driven later from
     *  {@code Nrof#onInitialize}, by which point Minecraft's registry stage is
     *  already complete. */
    private static volatile CachedBootstrap BOOTSTRAP = new CachedBootstrap(List.of(), null);

    /** Result tuple kept for late boot. */
    private record CachedBootstrap(List<CachedNeoForgeMod> mods, ClassLoader classLoader) {}

    public static boolean hasBootstrappedMods() {
        return !REGISTERED.get() || !BOOTSTRAP.mods.isEmpty();
    }

    /** Called by entrypoints to run the NeoForge lifecycle on the pre-modded index. */
    public static void bootMods() {
        CachedBootstrap snap = BOOTSTRAP;
        if (snap.mods.isEmpty()) {
            return;
        }
        try {
            NrofModRuntime.boot(snap.mods, snap.classLoader);
        } catch (Throwable t) {
            Nrof.LOGGER.error("[NROF] failed to drive NeoForge mod lifecycle from late boot", t);
        }
    }

    public static void registerConvertedMods() {
        if (!REGISTERED.compareAndSet(false, true)) {
            return;
        }
        try {
            List<CachedNeoForgeMod> ready = NrofRuntime.runPipeline();
            if (ready.isEmpty()) {
                return;
            }
            int count = registerIntoLoaderIndex(ready);
            Nrof.LOGGER.info("[NROF] registered {} converted NeoForge mod(s) into the fabric mod index", count);

            // Make the converted mods' classes/resources loadable NOW, so any
            // Fabric entrypoints (including nrof's own) that refer to NeoForge
            // mod classes at init-time can resolve them. The lifecycle stage —
            // @Mod construction, FMLConstructModEvent, CommonSetup … — is
            // deferred into the Fabric entrypoint path; registries are not
            // bootstrapped yet at this early stage and any static initializer
            // touching BuiltInRegistries/RegistryKeys would throw
            // "Not bootstrapped" when Minecraft.initBootstrapStamps hasn't run.
            ClassLoader knotClassLoader = Thread.currentThread().getContextClassLoader();
            mountClasses(knotClassLoader, ready);
            BOOTSTRAP = new CachedBootstrap(List.copyOf(ready), knotClassLoader);
        } catch (Throwable t) {
            Nrof.LOGGER.error("[NROF] failed to register converted NeoForge mods into the fabric index", t);
        }
    }

    /**
     * Adds the cached conversion directories to the game knot classloader
     * ({@code KnotClassLoader.addUrlFwd} — classes were built before the game
     * classloader finished wiring, so post-hoc mount is the only option; every
     * game class still loads after this point).
     */
    private static void mountClasses(ClassLoader classLoader, List<CachedNeoForgeMod> mods) {
        try {
            java.lang.reflect.Method addUrl = classLoader.getClass().getMethod("addUrlFwd", java.net.URL.class);
            addUrl.setAccessible(true);
            for (CachedNeoForgeMod mod : mods) {
                // Nested JiJ jars first: the main jar's classes link against the
                // embedded libraries (Registrate/Ponder/...), and the knot loader
                // searches URLs in mount order.
                mountNestedJars(addUrl, classLoader, mod);
                // remapped jar (intermediary-named) preferred; then cache dirs
                // (future pre-transformed overrides)
                Path remapped = mod.entryDir().resolve("remapped.jar");
                Path sourceJar = java.nio.file.Files.isRegularFile(remapped) ? remapped : mod.originJar();
                addUrl.invoke(classLoader, sourceJar.toUri().toURL());
                addUrl.invoke(classLoader, mod.entryDir().toUri().toURL());
                addUrl.invoke(classLoader, mod.classesDir().toUri().toURL());
            }
        } catch (Throwable t) {
            Nrof.LOGGER.error("[NROF] failed to mount cached mod classes onto the knot classloader", t);
        }
    }

    /**
     * Mounts every jar inside the cache entry's {@code nested-jars/} directory.
     * Remapped copies sort before their raw counterparts ({@code -} < {@code .}),
     * so the intermediary-named variant is found first.
     */
    private static void mountNestedJars(java.lang.reflect.Method addUrl, ClassLoader classLoader,
                                        CachedNeoForgeMod mod) throws Exception {
        Path dir = com.cat.nrof.transform.ModPreTransformer.nestedJarsDir(mod.entryDir());
        if (!java.nio.file.Files.isDirectory(dir)) {
            return;
        }
        try (var stream = java.nio.file.Files.list(dir)) {
            for (Path jar : stream.filter(p -> p.getFileName().toString().endsWith(".jar")).sorted().toList()) {
                addUrl.invoke(classLoader, jar.toUri().toURL());
            }
        }
    }

    private static int registerIntoLoaderIndex(List<CachedNeoForgeMod> ready) throws Exception {
        FabricLoaderImpl loader = (FabricLoaderImpl) net.fabricmc.loader.api.FabricLoader.getInstance();

        // --- reflection handles into loader internals (core package, app classloader) ---
        Class<?> candidateCls = Class.forName("net.fabricmc.loader.impl.discovery.ModCandidateImpl");
        Method createPlain = candidateCls.getDeclaredMethod("createPlain",
                List.class, LoaderModMetadata.class, boolean.class, Collection.class);
        createPlain.setAccessible(true);

        Class<?> containerCls = Class.forName("net.fabricmc.loader.impl.ModContainerImpl");
        Constructor<?> containerCtor = containerCls.getDeclaredConstructor(candidateCls);
        containerCtor.setAccessible(true);

        Field modsField = FabricLoaderImpl.class.getDeclaredField("mods");
        modsField.setAccessible(true);
        @SuppressWarnings("unchecked")
        List<Object> mods = (List<Object>) modsField.get(loader);

        Field modMapField = FabricLoaderImpl.class.getDeclaredField("modMap");
        modMapField.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<String, Object> modMap = (Map<String, Object>) modMapField.get(loader);

        VersionOverrides versionOverrides = new VersionOverrides();
        DependencyOverrides depOverrides = new DependencyOverrides(
                loader.getGameDir().resolve(".nrof"));

        int count = 0;
        for (CachedNeoForgeMod mod : ready) {
            if (modMap.containsKey(mod.sourceModId())) {
                continue; // already present (e.g. a same-id fabric mod) — never shadow real mods
            }
            try {
                LoaderModMetadata metadata = parseMetadata(mod, versionOverrides, depOverrides);
                Object candidate = createPlain.invoke(null,
                        List.of(mod.entryDir(), mod.classesDir()), metadata, false, List.of());
                Object container = containerCtor.newInstance(candidate);
                mods.add(container);
                modMap.put(metadata.getId(), container);
                count++;
            } catch (Throwable t) {
                Nrof.LOGGER.error("[NROF] failed to register cached mod '{}' into the fabric index",
                        mod.sourceModId(), t);
            }
        }
        return count;
    }

    private static LoaderModMetadata parseMetadata(CachedNeoForgeMod mod,
                                                   VersionOverrides vo,
                                                   DependencyOverrides dop) throws Exception {
        try (InputStream in = Files.newInputStream(mod.metadataJson())) {
            return ModMetadataParser.parseMetadata(in, mod.sourceModId(), List.of(), vo, dop, false);
        }
    }

    private NrofLoaderHook() {
    }
}
