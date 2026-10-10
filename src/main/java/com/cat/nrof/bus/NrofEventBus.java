package com.cat.nrof.bus;

import com.cat.nrof.bus.event.NrofMixinConfigEvent;
import com.cat.nrof.bus.event.NrofModScannedEvent;
import com.cat.nrof.discovery.DiscoveredNeoForgeMod;
import net.neoforged.bus.api.BusBuilder;
import net.neoforged.bus.api.Event;
import net.neoforged.bus.api.IEventBus;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * The ported NeoForge event-driven bus. Wraps the real {@code net.neoforged.bus}
 * implementation ({@link BusBuilder} / {@link IEventBus}) so NeoForge-style
 * {@code @SubscribeEvent} listeners and event dispatch behave the same as on
 * NeoForge itself.
 *
 * <p>NROF uses this bus for its own orchestration — most notably to drive mod
 * metadata acquisition and Mixin-config recognition:</p>
 * <ol>
 *   <li>{@link NrofModScannedEvent} — fired for every discovered NeoForge jar.</li>
 *   <li>{@link NrofMixinConfigEvent} — fired per mixin config; listeners may cancel
 *       it to keep a config away from Fabric, otherwise it is forwarded to Fabric's
 *       mixin pipeline through the converted metadata.</li>
 * </ol>
 */
public final class NrofEventBus {

    private final IEventBus backend;

    private NrofEventBus(IEventBus backend) {
        this.backend = backend;
    }

    public static NrofEventBus create() {
        return new NrofEventBus(BusBuilder.builder().build());
    }

    /** Registers an {@code @SubscribeEvent}-annotated listener object, NeoForge style. */
    public void register(Object listener) {
        backend.register(listener);
    }

    public void unregister(Object listener) {
        backend.unregister(listener);
    }

    /** Lambda listener without explicit type (inference via the consumer). */
    public <T extends Event> void addListener(Consumer<T> listener) {
        backend.addListener(listener);
    }

    /** Lambda listener with explicit event type. */
    public <T extends Event> void addListener(Class<T> eventType, Consumer<T> listener) {
        backend.addListener(eventType, listener);
    }

    public <T extends Event> T post(T event) {
        return backend.post(event);
    }

    /** Finalizes listener registration (per-phase post support), matching FML behavior. */
    public void start() {
        backend.start();
    }

    IEventBus backend() {
        return backend;
    }

    /**
     * Fires {@link NrofModScannedEvent} for the given mod and returns the mixin
     * configs the bus decided to forward to Fabric (i.e. not cancelled).
     */
    public List<String> recognizeMixinConfigs(DiscoveredNeoForgeMod mod) {
        post(new NrofModScannedEvent(mod));
        List<String> forwarded = new ArrayList<>();
        for (String config : mod.mixinConfigs()) {
            NrofMixinConfigEvent ev = post(new NrofMixinConfigEvent(mod, config));
            if (!ev.isCanceled()) {
                forwarded.add(config);
            }
        }
        return forwarded;
    }
}
