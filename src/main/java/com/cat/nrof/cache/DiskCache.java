package com.cat.nrof.cache;

import com.cat.nrof.Nrof;
import com.cat.nrof.NrofVersion;
import com.cat.nrof.convert.NrofModMetadata;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.function.Function;
import java.util.stream.Stream;

/**
 * On-disk cache for converted NeoForge mods. Fabric loads lazily, so every
 * conversion / pre-build result must be materialized here — keeping it in
 * memory would be far too heavy. Cache entries are keyed by the source jar's
 * content identity and invalidated automatically by the cache format version.
 *
 * <pre>
 * &lt;gameDir&gt;/.nrof/cache/
 *   &lt;sha256 key&gt;/
 *     fabric.mod.json   — converted metadata (Fabric binary structure)
 *     origin.txt        — provenance of the entry
 *     classes/          — pre-built / transformed classes
 * </pre>
 */
public final class DiskCache {

    private final Path root;

    public DiskCache(Path root) {
        this.root = root;
        try {
            Files.createDirectories(root);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot create NROF cache dir " + root, e);
        }
    }

    public Path root() {
        return root;
    }

    /**
     * Returns the cache entry for {@code key}, computing it via {@code producer}
     * on a miss. The producer receives the freshly created entry directory and
     * must return the converted metadata.
     */
    public CachedNeoForgeMod getOrCompute(String key, Path originJar, Function<Path, NrofModMetadata> producer) {
        Path entry = root.resolve(key);
        Path metaJson = entry.resolve("fabric.mod.json");

        NrofModMetadata cached = readMetadata(metaJson);
        if (cached != null) {
            Nrof.LOGGER.debug("[NROF] cache hit for key {}", key);
            return new CachedNeoForgeMod(
                    cached.sourceModId(), originJar, entry, metaJson, entry.resolve("classes"), cached);
        }

        try {
            Files.createDirectories(entry);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }

        NrofModMetadata computed = producer.apply(entry);
        writeAtomic(metaJson, computed.toJson().toString());
        writeAtomic(entry.resolve("origin.txt"), computed.sourceModId() + "\n" + NrofVersion.CACHE_FORMAT);
        Nrof.LOGGER.info("[NROF] cached converted mod '{}' -> {}", computed.sourceModId(), metaJson);
        return new CachedNeoForgeMod(computed.sourceModId(), originJar, entry, metaJson, entry.resolve("classes"), computed);
    }

    private NrofModMetadata readMetadata(Path metaJson) {
        if (!Files.isRegularFile(metaJson)) {
            return null;
        }
        try {
            JsonObject json = JsonParser.parseString(Files.readString(metaJson, StandardCharsets.UTF_8)).getAsJsonObject();
            JsonObject nrofBlock = json.getAsJsonObject("custom").getAsJsonObject("nrof");
            if (nrofBlock == null || !NrofVersion.CACHE_FORMAT.equals(asString(nrofBlock, "cache-format"))) {
                return null; // format drift -> recompute
            }
            return fromJson(json);
        } catch (Exception e) {
            Nrof.LOGGER.warn("[NROF] unreadable cache entry {}, recomputing: {}", metaJson, e.toString());
            return null;
        }
    }

    private static String asString(JsonObject o, String key) {
        return o.has(key) && o.get(key).isJsonPrimitive() ? o.get(key).getAsString() : null;
    }

    /** Rebuilds the in-memory metadata from its serialized Fabric structure. */
    static NrofModMetadata fromJson(JsonObject json) {
        JsonObject nrofBlock = json.getAsJsonObject("custom").getAsJsonObject("nrof");
        NrofModMetadata meta = new NrofModMetadata(
                asString(nrofBlock, "source-mod-id"),
                json.get("id").getAsString(),
                json.get("version").getAsString(),
                json.has("name") ? json.get("name").getAsString() : null,
                json.has("description") ? json.get("description").getAsString() : null);
        if (json.has("authors")) {
            json.getAsJsonArray("authors").forEach(a -> meta.authors.add(a.getAsString()));
        }
        if (json.has("mixins")) {
            json.getAsJsonArray("mixins").forEach(a -> meta.mixins.add(a.getAsString()));
        }
        if (json.has("depends")) {
            json.getAsJsonObject("depends").entrySet().forEach(e -> meta.depends.put(e.getKey(), e.getValue().getAsString()));
        }
        if (json.has("suggests")) {
            json.getAsJsonObject("suggests").entrySet().forEach(e -> meta.suggests.put(e.getKey(), e.getValue().getAsString()));
        }
        meta.nrofCustom.add("runtime", nrofBlock);
        return meta;
    }

    private static void writeAtomic(Path target, String content) {
        Path tmp = null;
        try {
            tmp = Files.createTempFile(target.getParent(), target.getFileName().toString(), ".tmp");
            Files.writeString(tmp, content, StandardCharsets.UTF_8);
            try {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("failed to write " + target, e);
        } finally {
            if (tmp != null) {
                try {
                    Files.deleteIfExists(tmp);
                } catch (IOException ignored) {
                }
            }
        }
    }

    /** Wipes stale entries whose keys are no longer referenced. Callers may use this opportunistically. */
    public void prune(Stream<String> liveKeys) {
        List<String> live = liveKeys.toList();
        try (Stream<Path> entries = Files.list(root)) {
            for (Path dir : entries.toList()) {
                if (Files.isDirectory(dir) && !live.contains(dir.getFileName().toString())) {
                    deleteRecursively(dir);
                }
            }
        } catch (IOException e) {
            Nrof.LOGGER.warn("[NROF] cache prune failed: {}", e.toString());
        }
    }

    private static void deleteRecursively(Path dir) throws IOException {
        try (Stream<Path> walk = Files.walk(dir)) {
            walk.sorted((a, b) -> b.getNameCount() - a.getNameCount()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                }
            });
        }
    }
}
