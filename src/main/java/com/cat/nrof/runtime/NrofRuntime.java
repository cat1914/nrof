package com.cat.nrof.runtime;

import com.cat.nrof.Nrof;
import com.cat.nrof.bus.NrofEventBus;
import com.cat.nrof.cache.CachedNeoForgeMod;
import com.cat.nrof.cache.DiskCache;
import com.cat.nrof.convert.FabricMetadataBuilder;
import com.cat.nrof.discovery.DiscoveredNeoForgeMod;
import com.cat.nrof.discovery.NeoForgeModDiscoverer;
import com.cat.nrof.transform.ModPreTransformer;
import net.fabricmc.loader.api.FabricLoader;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Orchestrates the NROF pipeline:
 * <ol>
 *   <li><b>Discover</b> — scan the mods dir for NeoForge jars (neoforge.mods.toml).</li>
 *   <li><b>Bus</b> — the ported NeoForge event bus acquires mod metadata context and
 *       recognizes mixin configs (event-driven, like on NeoForge).</li>
 *   <li><b>Convert</b> — metadata is converted into Fabric's structure.</li>
 *   <li><b>Pre-build + cache</b> — results are materialized to disk (Fabric is lazy;
 *       nothing may live only in memory).</li>
 * </ol>
 *
 * <p>The pipeline is idempotent. It normally runs from the loader index hook
 * ({@code ModDiscovererMixin}) — i.e. <em>before</em> Fabric's mod resolution so
 * the converted mods can join the index. If the hook is inactive it falls back to
 * running from {@code Nrof#onInitialize} (conversion still works, index injection
 * is deferred).</p>
 */
public final class NrofRuntime {

    private static final AtomicBoolean RAN = new AtomicBoolean(false);
    private static volatile List<CachedNeoForgeMod> lastResult = List.of();

    public static boolean hasRun() {
        return RAN.get();
    }

    public static List<CachedNeoForgeMod> runPipeline() {
        if (!RAN.compareAndSet(false, true)) {
            return lastResult;
        }
        FabricLoader loader = FabricLoader.getInstance();
        Path modsDir = loader.getGameDir().resolve("mods");
        Path cacheDir = loader.getGameDir().resolve(".nrof").resolve("cache");
        DiskCache cache = new DiskCache(cacheDir);

        List<DiscoveredNeoForgeMod> discovered = NeoForgeModDiscoverer.discover(modsDir);
        if (discovered.isEmpty()) {
            Nrof.LOGGER.info("[NROF] no NeoForge mods found in {}", modsDir);
            lastResult = List.of();
            return lastResult;
        }

        NrofEventBus bus = NrofEventBus.create();
        List<CachedNeoForgeMod> ready = new ArrayList<>();

        for (DiscoveredNeoForgeMod mod : discovered) {
            // Bus-driven metadata acquisition + mixin recognition happen exactly
            // once per launch; only the (expensive) conversion result is cached.
            List<String> forwarded = bus.recognizeMixinConfigs(mod);

            CachedNeoForgeMod cached = cache.getOrCompute(mod.contentKey(), mod.originPath(), entryDir -> {
                var metadata = FabricMetadataBuilder.fromNeoForge(mod, mod.infos().get(0), forwarded);
                ModPreTransformer.preBuild(mod,
                        new CachedNeoForgeMod(metadata.sourceModId(), mod.originPath(), entryDir,
                                entryDir.resolve("fabric.mod.json"), entryDir.resolve("classes"), metadata),
                        forwarded);
                return metadata;
            });
            ready.add(cached);
        }
        bus.start();

        Nrof.LOGGER.info("[NROF] {} NeoForge mod(s) converted:", ready.size());
        for (CachedNeoForgeMod mod : ready) {
            Nrof.LOGGER.info("[NROF]   - {} {} -> {} (mixins: {})",
                    mod.sourceModId(), mod.metadata().version,
                    mod.metadataJson(), mod.metadata().mixins.size());
        }

        lastResult = List.copyOf(ready);
        return lastResult;
    }

    private NrofRuntime() {
    }
}
