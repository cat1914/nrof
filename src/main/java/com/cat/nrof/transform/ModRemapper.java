package com.cat.nrof.transform;

import com.cat.nrof.Nrof;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * Remaps a NeoForge mod jar from mojang's namespace (what NeoForge mods are
 * compiled against) to Fabric's runtime namespace (intermediary).
 *
 * <p>NeoForge mods and the whole NeoForge API module link Minecraft classes
 * under mojmap names ({@code net.minecraft.server.level.ServerLevel, ...}),
 * while Fabric's runtime speaks intermediary ({@code class_XXXX}). Without this
 * pass not a single Minecraft reference resolves.</p>
 *
 * <p>Uses the composed {@code mojmap -> intermediary} mapping bundled in the
 * jar and a flat lookup-table remapper (see {@link TableRemapper}) — a single
 * direct pass with no class environment and no conflict propagation.</p>
 */
public final class ModRemapper {

    private static final String COMPOSED_TINY = "/nrof/mappings/mojmap-intermediary-1.21.1.tiny.gz";

    /**
     * Remaps {@code originJar} (mojmap-named) and writes the intermediary-named
     * result to {@code outJar}. Failures are logged and swallowed — the caller
     * falls back to mounting the origin jar.
     */
    public static void remapToIntermediary(Path originJar, Path outJar) {
        try {
            Path tiny = extract(COMPOSED_TINY, "nrof-mojmap-intermediary.tiny.gz");
            TableRemapper remapper = TableRemapper.loadTinyGz(tiny);
            Files.deleteIfExists(outJar);
            remapper.remapJar(originJar, outJar);
            Files.deleteIfExists(tiny);
            Nrof.LOGGER.info("[NROF] remapped {} -> {} (mojmap -> intermediary, single pass)",
                    originJar.getFileName(), outJar.getFileName());
        } catch (Throwable t) {
            Nrof.LOGGER.error("[NROF] remap failed for {} — mods will not link against Minecraft classes",
                    originJar, t);
        }
    }

    private static Path extract(String resource, String fileName) throws IOException {
        Path out = Files.createTempFile(fileName, "");
        try (InputStream in = ModRemapper.class.getResourceAsStream(resource)) {
            if (in == null) {
                throw new IOException("missing bundled mapping resource " + resource);
            }
            Files.copy(in, out, StandardCopyOption.REPLACE_EXISTING);
        }
        return out;
    }

    private ModRemapper() {
    }
}
