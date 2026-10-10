package com.cat.nrof.discovery;

import com.cat.nrof.model.NeoForgeModInfo;

import java.nio.file.Path;
import java.util.List;

/**
 * A jar on disk that contains {@code META-INF/neoforge.mods.toml}.
 *
 * @param originPath   path of the source jar
 * @param contentKey   stable cache key derived from the jar's identity
 * @param infos        every mod declared inside the jar (NeoForge jars may be multi-mod)
 * @param mixinConfigs all mixin config resource paths found inside the jar
 */
public record DiscoveredNeoForgeMod(
        Path originPath,
        String contentKey,
        List<NeoForgeModInfo> infos,
        List<String> mixinConfigs) {

    public DiscoveredNeoForgeMod {
        infos = List.copyOf(infos);
        mixinConfigs = List.copyOf(mixinConfigs);
    }
}
