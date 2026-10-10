package com.cat.nrof.convert;

/**
 * Forgified Fabric API identity handling, on the NeoForge side.
 *
 * <p>Forgified Fabric API keeps the real Fabric API's module ids for maximum
 * compatibility, adapted to NeoForge mod-id rules (no hyphens): the umbrella
 * mod id is {@code fabric_api} and per-module ids are the underscore forms
 * ({@code fabric_api_base}, {@code fabric_transfer_api_v1}, ...). Inside mod
 * code, the API being called is still the Fabric API itself
 * ({@code net.fabricmc.fabric.api.*} classes) — those classes are already
 * present on the Fabric classpath as the real Fabric API, which acts as the
 * framework's backend with identical FQNs, so no class-level proxying is
 * needed.</p>
 *
 * <p>NROF carries the framework on the NeoForge side at the identity layer:</p>
 * <ul>
 *   <li>Dependencies on framework modules normalize to {@code nrof}, the mod
 *       that embodies the framework in this runtime.</li>
 *   <li>A separately installed Forgified Fabric API jar must NOT be
 *       converted/loaded — the framework is built in, and loading a second
 *       copy would shadow the real Fabric API classes. The discoverer skips
 *       distribution jars whose entries are all framework module ids.</li>
 * </ul>
 */
public final class FfapiIds {

    /** The Forgified Fabric API umbrella mod id (real Fabric API id, underscore form). */
    public static final String FFAPI_MOD_ID = "fabric_api";

    /** The NeoForge-side framework provider: NROF itself. */
    public static final String FRAMEWORK_PROVIDER = "nrof";

    private FfapiIds() {
    }

    /**
     * Whether the given NeoForge mod-id refers to a Forgified Fabric API
     * framework module: the {@code fabric_api} umbrella or any
     * {@code fabric_*} per-module id. Real Fabric mods never show up as
     * NeoForge dependencies, so any {@code fabric_*} id inside a
     * {@code neoforge.mods.toml} is necessarily a framework module.
     */
    public static boolean isFfapiModule(String modId) {
        if (modId == null || modId.equals("fabricloader")) {
            return false;
        }
        return modId.equals("forgified_fabric_api") || modId.startsWith("fabric_");
    }

    /** Whether the id is a Forgified Fabric API umbrella distribution id. */
    public static boolean isFfapiUmbrella(String modId) {
        return FFAPI_MOD_ID.equals(modId) || "forgified_fabric_api".equals(modId);
    }
}
