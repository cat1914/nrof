package com.cat.nrof.model;

/**
 * A single {@code [[dependencies.<modId>]]} entry of {@code neoforge.mods.toml}.
 */
public record NeoForgeDependency(
        String modId,
        String type, // required | optional | embedding | incompatible | discard
        String versionRange,
        String ordering, // before | after | none
        String side) { // client | server | both

    public boolean isOptional() {
        return "optional".equalsIgnoreCase(type);
    }

    public boolean isIncompatible() {
        return "incompatible".equalsIgnoreCase(type);
    }
}
