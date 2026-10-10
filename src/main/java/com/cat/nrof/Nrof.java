package com.cat.nrof;

import com.cat.nrof.loader.NrofLoaderHook;
import com.cat.nrof.runtime.NrofRuntime;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * NROF main entrypoint (server+client; runs after registry bootstrap).
 *
 * <p>Responsibilities:</p>
 * <ol>
 *   <li>Drive the NeoForge runtime lifecycle on converted mods — the earlier
 *       Main-mixin hook only performs index registration / class mounting so
 *       registries are not referenced early (any call to BuiltInRegistries /
 *       RegistryKey before Minecraft finishes bootstrapping throws
 *       "Not bootstrapped").</li>
 *   <li>Bridge Fabric's {@link CommandRegistrationCallback} into the
 *       NeoForge RegisterCommandsEvent so NeoForge mods register their
 *       brigadier commands exactly like they do on NeoForge.</li>
 * </ol>
 *
 * <p>All Minecraft-class references inside command/UI bridges are resolved by
 * reflection. This module is compiled against yarn (the dev-launcher uses
 * intermediary names), but the runtime remaps to the same namespace anyway,
 * so reflection keeps the source compile-clean without hard-coding yarn
 * class_xxx names.</p>
 */
public class Nrof implements ModInitializer {
    public static final String MODID = "nrof";
    public static final Logger LOGGER = LoggerFactory.getLogger("NROF");

    @Override
    public void onInitialize() {
        if (NrofRuntime.hasRun()) {
            LOGGER.info("[NROF] pipeline already ran from the loader index hook");
        } else {
            LOGGER.warn("[NROF] loader index hook inactive — running pipeline late");
            NrofRuntime.runPipeline();
        }

        // ---- Stage 1: @Mod-construct + NeoForge lifecycle ----
        // Must happen AFTER registry bootstrap (Minecraft.initBootstrapStamps /
        // RegistryManager.bootstrapStamps). Running this in Main.mixin HEAD
        // caused: "Not bootstrapped (called from registry …/game_event)" when
        // Create's AllSoundEvents static initializer registered sound events
        // via NeoForge's Registry layer.
        NrofLoaderHook.bootMods();

        // ---- Stage 2: Command bridge ----
        try {
            Class.forName("net.neoforged.neoforge.event.RegisterCommandsEvent");
            CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
                Object evt = buildRegisterCommandsEvent(dispatcher, registryAccess, environment);
                if (evt != null) postNeoForgeBus(evt);
            });
            LOGGER.info("[NROF] command bridge registered: CommandRegistrationCallback → NeoForge RegisterCommandsEvent");
        } catch (ClassNotFoundException e) {
            LOGGER.warn("[NROF] RegisterCommandsEvent class not present in bundled neoForge-api — command bridge disabled");
        } catch (Throwable t) {
            LOGGER.error("[NROF] command bridge registration failed", t);
        }
    }

    /**
     * Constructs a RegisterCommandsEvent reflectively. Package path is
     * net.neoforged.neoforge.event.RegisterCommandsEvent. Known ctor shapes
     * (varies by NeoForge release):
     * <ul>
     *   <li>21.1.x packaged: (CommandDispatcher&lt;CommandSourceStack&gt;,
     *       RegistryAccess.Frozen, CommandSelection)</li>
     *   <li>older packaged: (CommandDispatcher, BuildContext, CommandSelection)</li>
     *   <li>earlier: (CommandDispatcher, CommandSelection) or (CommandDispatcher, BuildContext)</li>
     * </ul>
     * BuildContext may not be present on the classpath (it was a brief-lived
     * package-private intermediate record). Callers MUST NOT rely on
     * Class.forName("BuildContext") succeeding — fall back to assignable arg
     * matching instead.
     */
    private static Object buildRegisterCommandsEvent(Object dispatcher,
                                                     Object registryAccessOrBuildCtx,
                                                     Object commandSelection) {
        try {
            Class<?> eventCls = Class.forName("net.neoforged.neoforge.event.RegisterCommandsEvent");

            Class<?> buildCtxCls = null;
            Object buildCtx = registryAccessOrBuildCtx;
            try {
                buildCtxCls = Class.forName("net.neoforged.neoforge.event.RegisterCommandsEvent$BuildContext");
                // If present, BuildContext typically takes RegistryAccess /
                // RegistryAccess.Frozen / no-arg. Try to construct one from
                // the raw registry access we got from CommandSelection.
                for (java.lang.reflect.Constructor<?> c : buildCtxCls.getDeclaredConstructors()) {
                    c.setAccessible(true);
                    Class<?>[] pt = c.getParameterTypes();
                    try {
                        if (pt.length == 0) {
                            buildCtx = c.newInstance();
                            break;
                        }
                        if (registryAccessOrBuildCtx != null &&
                            pt.length == 1 && pt[0].isInstance(registryAccessOrBuildCtx)) {
                            buildCtx = c.newInstance(registryAccessOrBuildCtx);
                            break;
                        }
                    } catch (Throwable ignore) {
                        // try next ctor
                    }
                }
            } catch (ClassNotFoundException ignored) {
                // no BuildContext in this bundled API — leave buildCtx as the
                // raw RegistryAccess (or null); ctor-match below handles it.
                buildCtx = registryAccessOrBuildCtx;
            }

            // Gather candidate args by position, then for each ctor, try to
            // take its parameters from the arg pool by assignability, filling
            // with null where we have nothing.
            Object[] pool = new Object[]{
                    dispatcher,                         // position 0 (always dispatcher)
                    buildCtx,                           // position 1 (BuildContext OR RegistryAccess OR null)
                    commandSelection,                   // position 2 (CommandSelection OR null)
                    registryAccessOrBuildCtx            // extra fallback for 21.1.x three-arg shapes
            };

            Throwable lastErr = null;
            for (java.lang.reflect.Constructor<?> ctor : eventCls.getDeclaredConstructors()) {
                ctor.setAccessible(true);
                Class<?>[] pt = ctor.getParameterTypes();
                Object[] args = new Object[pt.length];
                boolean[] used = new boolean[pool.length];
                boolean ok = true;
                outer:
                for (int i = 0; i < pt.length; i++) {
                    Class<?> need = pt[i];
                    Object chosen = null;
                    for (int k = 0; k < pool.length; k++) {
                        if (used[k]) continue;
                        Object p = pool[k];
                        if (p == null) continue;
                        if (need.isInstance(p)) {
                            chosen = p;
                            used[k] = true;
                            continue outer;
                        }
                    }
                    if (chosen == null) {
                        // dispatcher is known non-null first param in every
                        // shape; if we couldn't match, we'd misfire — mark fail.
                        if (i == 0) { ok = false; break; }
                        // nullable types: fall back to null
                        if (need.isPrimitive()) { ok = false; break; }
                    }
                    args[i] = chosen;
                }
                if (!ok) continue;
                try {
                    return ctor.newInstance(args);
                } catch (Throwable t) {
                    lastErr = t;
                }
            }
            throw new RuntimeException("no suitable RegisterCommandsEvent constructor found; " +
                    "args-dispatcher=" + (dispatcher == null ? "null" : dispatcher.getClass().getName()) +
                    ", buildCtx/registryAccess=" + (buildCtx == null ? "null" : buildCtx.getClass().getName()) +
                    ", commandSelection=" + (commandSelection == null ? "null" : commandSelection.getClass().getName()),
                    lastErr);
        } catch (Throwable t) {
            LOGGER.warn("[NROF] could not construct RegisterCommandsEvent — bridge pass skipped", t);
            return null;
        }
    }

    /** Posts the event through NeoForge.EVENT_BUS (if the class is bundled). */
    static void postNeoForgeBus(Object event) {
        if (event == null) return;
        try {
            Class<?> busHolder = Class.forName("net.neoforged.neoforge.common.NeoForge");
            java.lang.reflect.Field busField = busHolder.getField("EVENT_BUS");
            Object bus = busField.get(null);
            java.lang.reflect.Method post = bus.getClass().getMethod("post", net.neoforged.bus.api.Event.class);
            post.invoke(bus, event);
        } catch (ClassNotFoundException ignored) {
            LOGGER.debug("[NROF] NeoForge.EVENT_BUS missing from bundled API — global post skipped");
        } catch (Throwable t) {
            LOGGER.error("[NROF] could not post {} to NeoForge event bus",
                    event.getClass().getName(), t);
        }
    }
}
