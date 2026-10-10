package com.cat.nrof.runtime;

import net.neoforged.bus.api.BusBuilder;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.neoforgespi.language.IModInfo;

import java.lang.reflect.Constructor;
import java.lang.reflect.Modifier;

/**
 * {@link ModContainer} implementation for converted NeoForge mods, built on the
 * real FML {@code ModContainer} base class (JiJ'd fancymodloader jar) and the
 * real NeoForge event bus.
 *
 * <p>{@link #constructMod()} mirrors FML's javafml language provider: it locates
 * the {@code @Mod} class, instantiates it through one of the supported
 * constructors ({@code (IEventBus, ModContainer)}, {@code (ModContainer)},
 * {@code ()}) and registers its {@code @SubscribeEvent} methods on the mod's
 * event bus.</p>
 */
public final class NrofModContainer extends ModContainer {

    private final IEventBus eventBus;
    private final String modClassName;
    private final ClassLoader classLoader;
    private Object modInstance;

    public NrofModContainer(IModInfo info, String modClassName, ClassLoader classLoader) {
        super(info);
        this.eventBus = BusBuilder.builder().build();
        this.modClassName = modClassName;
        this.classLoader = classLoader;
    }

    @Override
    public IEventBus getEventBus() {
        return eventBus;
    }

    Object modInstance() {
        return modInstance;
    }

    @Override
    protected void constructMod() {
        Class<?> clazz;
        try {
            clazz = Class.forName(modClassName, true, classLoader);
        } catch (Throwable t) {
            throw new IllegalStateException("[NROF] failed to load @Mod class " + modClassName, t);
        }

        Object instance = instantiate(clazz);
        this.modInstance = instance;

        try {
            if (instance != null && hasSubscribeEvent(clazz, false)) {
                eventBus.register(instance);
            }
            if (hasSubscribeEvent(clazz, true)) {
                eventBus.register(clazz);
            }
        } catch (Throwable t) {
            throw new IllegalStateException("[NROF] failed to register event listeners of " + modClassName, t);
        }
    }

    private Object instantiate(Class<?> clazz) {
        // (IEventBus, ModContainer)
        try {
            Constructor<?> ctor = clazz.getDeclaredConstructor(IEventBus.class, ModContainer.class);
            ctor.setAccessible(true);
            return ctor.newInstance(eventBus, this);
        } catch (NoSuchMethodException ignored) {
            // fall through
        } catch (Throwable t) {
            throw new IllegalStateException("[NROF] constructor (IEventBus, ModContainer) of " + modClassName + " threw", t);
        }
        // (ModContainer)
        try {
            Constructor<?> ctor = clazz.getDeclaredConstructor(ModContainer.class);
            ctor.setAccessible(true);
            return ctor.newInstance(this);
        } catch (NoSuchMethodException ignored) {
            // fall through
        } catch (Throwable t) {
            throw new IllegalStateException("[NROF] constructor (ModContainer) of " + modClassName + " threw", t);
        }
        // ()
        try {
            Constructor<?> ctor = clazz.getDeclaredConstructor();
            ctor.setAccessible(true);
            return ctor.newInstance();
        } catch (NoSuchMethodException ignored) {
            // fall through
        } catch (Throwable t) {
            throw new IllegalStateException("[NROF] no-arg constructor of " + modClassName + " threw", t);
        }
        return null;
    }

    private static boolean hasSubscribeEvent(Class<?> clazz, boolean staticMethods) {
        for (Class<?> c = clazz; c != null && c != Object.class; c = c.getSuperclass()) {
            for (java.lang.reflect.Method m : c.getDeclaredMethods()) {
                if (m.isAnnotationPresent(net.neoforged.bus.api.SubscribeEvent.class)
                        && Modifier.isStatic(m.getModifiers()) == staticMethods) {
                    return true;
                }
            }
        }
        return false;
    }
}
