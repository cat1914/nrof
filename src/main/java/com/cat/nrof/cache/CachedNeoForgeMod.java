package com.cat.nrof.cache;

import com.cat.nrof.convert.NrofModMetadata;

import java.nio.file.Path;

/**
 * A NeoForge mod whose converted metadata (and optionally pre-built classes)
 * has been materialized on disk.
 *
 * @param sourceModId  the originating NeoForge mod id
 * @param originJar    the original NeoForge mod jar (still the class source until
 *                     bytecode-level pre-transformation lands)
 * @param entryDir     cache entry directory (holds fabric.mod.json + classes/)
 * @param metadataJson path of the generated fabric.mod.json document
 * @param classesDir   directory for pre-built/transformed classes
 * @param metadata     in-memory converted metadata
 */
public record CachedNeoForgeMod(
        String sourceModId,
        Path originJar,
        Path entryDir,
        Path metadataJson,
        Path classesDir,
        NrofModMetadata metadata) {
}
