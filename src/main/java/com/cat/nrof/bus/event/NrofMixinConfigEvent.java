package com.cat.nrof.bus.event;

import com.cat.nrof.discovery.DiscoveredNeoForgeMod;
import net.neoforged.bus.api.ICancellableEvent;
import net.neoforged.bus.api.Event;

/**
 * Fired on the ported bus for every Mixin configuration encountered in a
 * NeoForge mod. This is the ported equivalent of NeoForge's event-driven Mixin
 * handling: listeners can inspect (and later rewrite) the config, and cancelling
 * the event prevents the config from being forwarded to Fabric.
 */
public class NrofMixinConfigEvent extends Event implements ICancellableEvent {
    private final DiscoveredNeoForgeMod mod;
    private final String mixinConfig;
    private String rewriteTarget;

    public NrofMixinConfigEvent(DiscoveredNeoForgeMod mod, String mixinConfig) {
        this.mod = mod;
        this.mixinConfig = mixinConfig;
    }

    public DiscoveredNeoForgeMod getMod() {
        return mod;
    }

    public String getMixinConfig() {
        return mixinConfig;
    }

    /**
     * Optionally redirects the forwarded config to a different resource path
     * (e.g. a Fabric-adjusted copy generated during pre-build).
     */
    public void rewriteTarget(String newConfigPath) {
        this.rewriteTarget = newConfigPath;
    }

    public String getForwardTarget() {
        return rewriteTarget != null ? rewriteTarget : mixinConfig;
    }
}
