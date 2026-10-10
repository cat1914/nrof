package com.cat.nrof.bus.event;

import com.cat.nrof.discovery.DiscoveredNeoForgeMod;
import net.neoforged.bus.api.Event;

/**
 * Fired on the ported bus when a NeoForge jar has been discovered and parsed.
 */
public class NrofModScannedEvent extends Event {
    private final DiscoveredNeoForgeMod mod;

    public NrofModScannedEvent(DiscoveredNeoForgeMod mod) {
        this.mod = mod;
    }

    public DiscoveredNeoForgeMod getMod() {
        return mod;
    }
}
