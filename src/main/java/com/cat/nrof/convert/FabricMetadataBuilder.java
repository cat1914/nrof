package com.cat.nrof.convert;

import com.cat.nrof.discovery.DiscoveredNeoForgeMod;
import com.cat.nrof.model.NeoForgeDependency;
import com.cat.nrof.model.NeoForgeModInfo;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.util.List;

/**
 * Converts ported NeoForge metadata ({@link NeoForgeModInfo}) into Fabric's
 * metadata structure ({@link NrofModMetadata}).
 *
 * <p>Conversion rules:</p>
 * <ul>
 *   <li>{@code neoforge} dependencies collapse onto {@code nrof} — NROF itself
 *       provides the NeoForge runtime on Fabric.</li>
 *   <li>NeoForge maven version ranges pass through unchanged; the Fabric loader
 *       understands maven ranges natively.</li>
 *   <li>Optional dependencies become {@code suggests}.</li>
 *   <li>Mixin configs recognized by the ported event bus are appended to
 *       {@code mixins}, handing them straight to Fabric's mixin pipeline.</li>
 * </ul>
 */
public final class FabricMetadataBuilder {

    public static NrofModMetadata fromNeoForge(DiscoveredNeoForgeMod mod,
                                               NeoForgeModInfo info,
                                               List<String> recognizedMixinConfigs) {
        NrofModMetadata meta = new NrofModMetadata(
                info.modId(), info.modId(), info.version(), info.displayName(), info.description());

        meta.authors.addAll(info.authors());
        meta.license = info.license();
        meta.icon = info.logoFile();
        meta.homepage = info.displayURL();
        meta.issues = info.issueTrackerURL();

        for (NeoForgeDependency dep : info.dependencies()) {
            if (dep.isIncompatible()) {
                continue; // fabric has no incompatible section; conflicts resolved at runtime
            }
            if (FfapiIds.isFfapiModule(dep.modId())) {
                // Forgified Fabric API is a NeoForge-side framework carried by
                // NROF: the dependency resolves against nrof itself. Version
                // ranges are dropped — the framework's interfaces are backed
                // by whatever real Fabric API is installed, so no version
                // constraint can meaningfully apply.
                if (dep.isOptional()) {
                    meta.suggests.put(FfapiIds.FRAMEWORK_PROVIDER, "*");
                } else {
                    meta.depends.put(FfapiIds.FRAMEWORK_PROVIDER, "*");
                }
                continue;
            }
            String target = normalizeDepId(dep.modId());
            if (dep.isOptional()) {
                meta.suggests.put(target, dep.versionRange());
            } else {
                meta.depends.put(target, dep.versionRange());
            }
        }
        // Fabric loader is always present.
        meta.depends.put("fabricloader", ">=0.15.0");

        meta.mixins.addAll(recognizedMixinConfigs);

        JsonObject nrofBlock = new JsonObject();
        nrofBlock.addProperty("source-mod-id", info.modId());
        nrofBlock.addProperty("cache-format", com.cat.nrof.NrofVersion.CACHE_FORMAT);
        nrofBlock.addProperty("origin-jar", mod.originPath().toString());
        nrofBlock.addProperty("origin-jar-key", mod.contentKey());
        nrofBlock.add("neoforge-metadata", serializeNeoForgeInfo(info));
        meta.nrofCustom.add("runtime", nrofBlock);

        return meta;
    }

    /** The NeoForge runtime is provided by NROF itself. */
    private static String normalizeDepId(String modId) {
        return switch (modId) {
            case "neoforge", "fml", "javafml", "minecraft" -> modId.equals("minecraft") ? "minecraft" : "nrof";
            default -> modId;
        };
    }

    private static JsonObject serializeNeoForgeInfo(NeoForgeModInfo info) {
        JsonObject o = new JsonObject();
        o.addProperty("modId", info.modId());
        o.addProperty("version", info.version());
        o.addProperty("displayName", info.displayName());
        o.addProperty("description", info.description());
        o.addProperty("license", info.license());
        o.addProperty("environment", info.environment());
        if (!info.authors().isEmpty()) {
            JsonArray arr = new JsonArray();
            info.authors().forEach(arr::add);
            o.add("authors", arr);
        }
        if (!info.mixinConfigs().isEmpty()) {
            JsonArray arr = new JsonArray();
            info.mixinConfigs().forEach(arr::add);
            o.add("mixinConfigs", arr);
        }
        JsonArray deps = new JsonArray();
        for (NeoForgeDependency d : info.dependencies()) {
            JsonObject dep = new JsonObject();
            dep.addProperty("modId", d.modId());
            dep.addProperty("type", d.type());
            dep.addProperty("versionRange", d.versionRange());
            dep.addProperty("side", d.side());
            deps.add(dep);
        }
        o.add("dependencies", deps);
        return o;
    }

    private FabricMetadataBuilder() {
    }
}
