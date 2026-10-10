package com.cat.nrof.runtime;

import com.cat.nrof.Nrof;
import com.cat.nrof.bridge.capability.CapabilityBridge;
import com.cat.nrof.bridge.energy.EnergyBridge;
import com.cat.nrof.cache.CachedNeoForgeMod;
import net.fabricmc.loader.api.FabricLoader;
import net.neoforged.fml.DeferredWorkQueue;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.neoforged.fml.event.lifecycle.FMLConstructModEvent;
import net.neoforged.fml.event.lifecycle.FMLLoadCompleteEvent;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Drives the real FML lifecycle for converted NeoForge mods, mirroring the
 * javafml language provider: construct the {@code @Mod} class, then fire the
 * standard lifecycle events on each mod's own event bus
 * (ConstructMod → RegisterCapabilities → CommonSetup → ClientSetup → LoadComplete).
 *
 * <p>The events are the REAL FML event classes and {@link NrofModContainer}
 * extends the REAL FML {@code ModContainer}, so mods see genuine
 * {@code net.neoforged.fml} types — not shims.</p>
 *
 * <p>RegisterCapabilitiesEvent is fired between ConstructMod and CommonSetup,
 * matching upstream FML behavior. After all mods finish booting, the recorded
 * provider tables are swept into Fabric API's BlockApiLookup/EntityApiLookup/
 * ItemApiLookup and into Tech Reborn's EnergyStorage for the FE capability —
 * the actual bridge lives in {@link CapabilityBridge} and {@link EnergyBridge}.</p>
 */
public final class NrofModRuntime {

    private static final String MOD_ANNOTATION_DESCRIPTOR = "Lnet/neoforged/fml/common/Mod;";

    /** All mod containers constructed this session, in construction order. */
    private static final List<NrofModContainer> CONTAINERS = new ArrayList<>();
    private static volatile boolean capabilitySweepDone = false;

    public static void boot(List<CachedNeoForgeMod> mods, ClassLoader classLoader) {
        initFmlPaths();
        boolean client = FabricLoader.getInstance().getEnvironmentType() == net.fabricmc.api.EnvType.CLIENT;
        for (CachedNeoForgeMod mod : mods) {
            try {
                bootMod(mod, classLoader, client);
            } catch (Throwable t) {
                logMissingApiChain("[NROF] lifecycle boot failed for mod '" + mod.sourceModId() + "'", t);
            }
        }
        sweepCapabilities();
    }

    /**
     * After every mod's RegisterCapabilitiesEvent has run, the global
     * BlockCapability/EntityCapability/ItemCapability provider tables are
     * populated. Push them into Fabric API's lookup system and wire the
     * bidirectional Tech Reborn Energy bridge.
     */
    private static void sweepCapabilities() {
        if (capabilitySweepDone) return;
        capabilitySweepDone = true;
        try {
            CapabilityBridge.sweepIntoFabricApi();
        } catch (Throwable t) {
            Nrof.LOGGER.error("[NROF] capability → Fabric API sweep failed", t);
        }
        try {
            EnergyBridge.install();
        } catch (Throwable t) {
            Nrof.LOGGER.error("[NROF] Tech Reborn Energy bridge install failed", t);
        }
    }

    /**
     * FML normally resolves its well-known paths (game dir, config dir, …)
     * during its own launch sequence, which NROF bypasses. NeoForge mods read
     * {@code FMLPaths.GAMEDIR.get()} during static init, so the paths must be
     * populated before any {@code @Mod} class loads.
     */
    private static void initFmlPaths() {
        try {
            net.neoforged.fml.loading.FMLPaths.loadAbsolutePaths(FabricLoader.getInstance().getGameDir());
        } catch (Throwable t) {
            Nrof.LOGGER.warn("[NROF] FMLPaths init failed — mods relying on FMLPaths will see null paths", t);
        }
    }

    private static void bootMod(CachedNeoForgeMod mod, ClassLoader classLoader, boolean client) throws IOException {
        List<String> modClassNames = findModClasses(mod.originJar());
        if (modClassNames.isEmpty()) {
            Nrof.LOGGER.warn("[NROF] no @Mod class found for converted mod '{}'", mod.sourceModId());
            return;
        }

        DeferredWorkQueue queue = new DeferredWorkQueue(mod.sourceModId());
        for (String className : modClassNames) {
            NrofModContainer container = new NrofModContainer(
                    new NrofModInfo(mod.metadata()), className, classLoader);

            // 1. construct (instantiates the @Mod class and wires its listeners)
            try {
                container.constructMod();
            } catch (Throwable t) {
                logMissingApiChain("[NROF] constructing @Mod class " + className + " failed", t);
                continue;
            }
            CONTAINERS.add(container);

            // 2. lifecycle events on the mod's own bus (real FML events)
            fireLifecycle(container, new FMLConstructModEvent(container, queue), "ConstructMod");

            // 2b. RegisterCapabilitiesEvent — fired by real FML between
            // ConstructMod and CommonSetup so mods can register capabilities
            // before any other mod (or the game) tries to query them.
            CapabilityBridge.fireRegisterCapabilities(container);

            fireLifecycle(container, new FMLCommonSetupEvent(container, queue), "CommonSetup");
            if (client) {
                fireLifecycle(container, new FMLClientSetupEvent(container, queue), "ClientSetup");
            }
            fireLifecycle(container, new FMLLoadCompleteEvent(container, queue), "LoadComplete");

            Nrof.LOGGER.info("[NROF] NeoForge mod '{}' lifecycle bootstrapped (class {})",
                    mod.sourceModId(), className);
        }
    }

    private static <T extends net.neoforged.bus.api.Event & net.neoforged.fml.event.IModBusEvent>
    void fireLifecycle(NrofModContainer container, T event, String stage) {
        try {
            container.acceptEvent(event);
            // DeferredWorkQueue tasks enqueued during the event run now, like FML does.
            runQueue(stage, container);
        } catch (Throwable t) {
            logMissingApiChain("[NROF] " + stage + " event dispatch failed", t);
        }
    }

    private static void runQueue(String stage, NrofModContainer container) {
        try {
            java.lang.reflect.Field f = DeferredWorkQueue.class.getDeclaredField("queues");
            f.setAccessible(true);
            Object queues = f.get(null);
            if (queues instanceof java.util.concurrent.ConcurrentMap<?, ?> map) {
                for (Object q : map.values()) {
                    q.getClass().getMethod("runTasks").invoke(q);
                }
            }
        } catch (NoSuchFieldException ignored) {
            // queue shape differs — tasks (if any) stay deferred; not fatal for boot
        } catch (Throwable t) {
            Nrof.LOGGER.debug("[NROF] deferred work queue drain after {} skipped: {}", stage, t.toString());
        }
    }

    /** Bytecode scan of the source jar for {@code @Mod} usage; only candidates are classloaded. */
    private static List<String> findModClasses(Path originJar) throws IOException {
        List<String> found = new ArrayList<>();
        if (!Files.isRegularFile(originJar)) {
            return found;
        }
        byte[] marker = MOD_ANNOTATION_DESCRIPTOR.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        try (java.util.jar.JarFile jar = new java.util.jar.JarFile(originJar.toFile())) {
            java.util.Enumeration<java.util.jar.JarEntry> entries = jar.entries();
            while (entries.hasMoreElements()) {
                java.util.jar.JarEntry entry = entries.nextElement();
                String name = entry.getName();
                if (!name.endsWith(".class") || name.contains("$")) {
                    continue;
                }
                byte[] bytes = jar.getInputStream(entry).readAllBytes();
                if (indexOf(bytes, marker) < 0) {
                    continue;
                }
                String className = name.substring(0, name.length() - ".class".length())
                        .replace('/', '.');
                found.add(className);
            }
        }
        return found;
    }

    private static int indexOf(byte[] haystack, byte[] needle) {
        outer:
        for (int i = 0; i <= haystack.length - needle.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (haystack[i + j] != needle[j]) {
                    continue outer;
                }
            }
            return i;
        }
        return -1;
    }

    /**
     * Logs an exception chain, highlighting the first missing class and
     * classifying it by backend: NeoForge API (shim/bridge backlog), Minecraft
     * class (remap gap), or other.
     */
    private static void logMissingApiChain(String message, Throwable t) {
        Throwable cur = t;
        while (cur != null) {
            if (cur instanceof NoClassDefFoundError ncde && ncde.getMessage() != null) {
                String missing = ncde.getMessage();
                String dotted = missing.replace('/', '.');
                if (missing.startsWith("net/neoforged/neoforge/")) {
                    Nrof.LOGGER.error("[NROF] {}: MISSING NeoForge API class '{}' — should be provided by the bundled neoforge-api module; check classpath/mount order",
                            message, dotted);
                } else if (missing.startsWith("net/minecraft/") || missing.startsWith("com/mojang/")) {
                    Nrof.LOGGER.error("[NROF] {}: MISSING Minecraft class '{}' — remap gap (pre-build mojmap->intermediary pass did not cover this reference)",
                            message, dotted);
                } else {
                    Nrof.LOGGER.error("[NROF] {}: MISSING class '{}' — needs a shim or a bundled dependency",
                            message, dotted);
                }
                Nrof.LOGGER.error("[NROF] root cause trace", t);
                return;
            }
            cur = cur.getCause();
        }
        Nrof.LOGGER.error(message, t);
    }

    private NrofModRuntime() {
    }
}
