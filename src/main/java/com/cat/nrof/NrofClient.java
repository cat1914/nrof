package com.cat.nrof;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import org.slf4j.Logger;

/**
 * NROF client entrypoint. Runs after client env + render thread are ready.
 *
 * <p>The bundled NeoForge API ships classes for client GUI events but its
 * constructors/signatures vary widely between builds and reference yarn-
 * internal class names we cannot hard-code at compile time. Everything here
 * uses reflective "try-hooks" so the module always compiles; hooks that
 * don't match the current api are logged at WARN and simply skip.</p>
 */
public class NrofClient implements ClientModInitializer {
    private static final Logger LOGGER = Nrof.LOGGER;

    @Override
    public void onInitializeClient() {
        LOGGER.info("[NROF] client bridge initializing");

        // ---- ScreenEvent.Init Pre / Post bridge ----------------------------------
        try {
            Class<?> preCls  = Class.forName("net.neoforged.neoforge.client.event.ScreenEvent$Init$Pre");
            Class<?> postCls = Class.forName("net.neoforged.neoforge.client.event.ScreenEvent$Init$Post");
            final java.lang.reflect.Constructor<?> preCtor  = firstAccessibleCtor(preCls);
            final java.lang.reflect.Constructor<?> postCtor = firstAccessibleCtor(postCls);

            ScreenEvents.BEFORE_INIT.register((client, screen, width, height) -> {
                if (preCtor != null) try {
                    Object evt = invokeBest(preCtor, screen);
                    if (evt != null) Nrof.postNeoForgeBus(evt);
                } catch (Throwable ignore) { /* soft-fail */ }
            });

            // AFTER_INIT ships an extra `loaders` arg whose type differs between
            // loom yarn builds; the BEFORE_INIT pass is sufficient for the
            // screen widget-add case.
            try {
                Class<?> afterInitClass = Class.forName(
                        "net.fabricmc.fabric.api.client.screen.v1.ScreenEvents$AfterInit");
                // Reflective registration so arg mismatch does not prevent compile.
                java.lang.reflect.Method reg = afterInitClass.getMethod("register", afterInitClass);
                Object lambda = java.lang.reflect.Proxy.newProxyInstance(
                        getClass().getClassLoader(),
                        new Class<?>[]{afterInitClass},
                        (_proxy, method, args) -> {
                            if (!method.getName().equals("afterInit")) return null;
                            // args: [client, screen, width, height, screenLoaders]
                            if (args != null && args.length >= 2 && postCtor != null) try {
                                Object evt = invokeBest(postCtor, args[1]);
                                if (evt != null) Nrof.postNeoForgeBus(evt);
                            } catch (Throwable ignore) { /* soft-fail */ }
                            return null;
                        });
                reg.invoke(null, lambda);
                LOGGER.info("[NROF] ScreenEvents.AFTER_INIT → ScreenEvent.Init.Post bridge installed");
            } catch (Throwable t) {
                LOGGER.debug("[NROF] AfterInit bridge skipped ({}): {}",
                        t.getClass().getSimpleName(), t.getMessage());
            }
            LOGGER.info("[NROF] ScreenEvents → NeoForge ScreenEvent.Init bridge installed (reflective)");
        } catch (ClassNotFoundException ignored) {
            LOGGER.debug("[NROF] NeoForge ScreenEvent.Init classes absent from bundled API — screen init bridge not installed");
        } catch (Throwable t) {
            LOGGER.warn("[NROF] ScreenEvent.Init bridge could not be installed", t);
        }

        LOGGER.info("[NROF] client bridge hooks installed");
    }

    private static java.lang.reflect.Constructor<?> firstAccessibleCtor(Class<?> cls) {
        for (java.lang.reflect.Constructor<?> c : cls.getDeclaredConstructors()) {
            c.setAccessible(true);
            return c;
        }
        return null;
    }

    /**
     * Tries to call a ctor given the screen object plus whatever the ctor wants
     * (screen alone, screen+children list, screen+list+addConsumer+removeConsumer …).
     * Returns null when the ctor cannot be satisfied.
     */
    private static Object invokeBest(java.lang.reflect.Constructor<?> ctor, Object screen) {
        Class<?>[] pt = ctor.getParameterTypes();
        Object[] args = new Object[pt.length];
        for (int i = 0; i < pt.length; i++) {
            if (pt[i].isInstance(screen)) {
                args[i] = screen;
            } else if (pt[i] == java.util.List.class) {
                Object list = tryGetChildren(screen);
                args[i] = list != null ? list : java.util.Collections.emptyList();
            } else if (pt[i] == java.util.function.Consumer.class) {
                args[i] = (java.util.function.Consumer<Object>) _x -> {};
            } else if (pt[i].isInterface() || pt[i].isPrimitive() || pt[i] == Object.class) {
                // Unknown but nullable; leave null and hope the ctor is tolerant.
                args[i] = null;
            } else {
                // Can't satisfy — abort this ctor.
                return null;
            }
        }
        try {
            return ctor.newInstance(args);
        } catch (Throwable t) {
            return null;
        }
    }

    /** Reflectively calls Screen.children() if possible, otherwise returns null. */
    private static Object tryGetChildren(Object screen) {
        try {
            java.lang.reflect.Method m = screen.getClass().getMethod("children");
            return m.invoke(screen);
        } catch (Throwable t) {
            return null;
        }
    }
}
