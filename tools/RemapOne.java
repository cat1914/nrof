import net.fabricmc.tinyremapper.IMappingProvider;
import net.fabricmc.tinyremapper.OutputConsumerPath;
import net.fabricmc.tinyremapper.TinyRemapper;
import net.fabricmc.tinyremapper.TinyUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * One-off build tool: remaps the bundled NeoForge API module jar from mojmap
 * to Fabric's intermediary namespace (two passes via official), so its
 * Minecraft references resolve on the production Fabric runtime.
 *
 * <p>Usage: {@code java RemapOne <in.jar> <out.jar> <mojmap-official.tiny[.gz]> <official-intermediary.tiny[.gz]>}</p>
 */
public final class RemapOne {

    public static void main(String[] args) throws IOException {
        Path in = Path.of(args[0]);
        Path out = Path.of(args[1]);
        Path mojmapTiny = gunzip(Path.of(args[2]));
        Path interTiny = gunzip(Path.of(args[3]));

        // unique name only — the file must not exist (OutputConsumerPath opens it as a zip)
        Path official = Files.createTempFile("nrof-api-official", ".jar");
        Files.delete(official);
        try {
            remap(in, official, mojmapTiny, "mojmap", "official");
            remap(official, out, interTiny, "official", "intermediary");
        } finally {
            Files.deleteIfExists(official);
        }
        System.out.println("remapped " + in + " -> " + out);
    }

    /** Bundled mappings are gzipped; tiny-remapper's TinyUtils needs plain text. */
    private static Path gunzip(Path gz) throws IOException {
        if (!gz.getFileName().toString().endsWith(".gz")) {
            return gz;
        }
        Path out = Files.createTempFile("nrof-mapping", ".tiny");
        try (var in = new java.util.zip.GZIPInputStream(Files.newInputStream(gz))) {
            Files.copy(in, out, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
        return out;
    }

    private static void remap(Path in, Path out, Path tiny, String srcNs, String dstNs) throws IOException {
        TinyRemapper remapper = TinyRemapper.newRemapper()
                .withMappings(mappings(tiny, srcNs, dstNs))
                .build();
        OutputConsumerPath output = new OutputConsumerPath.Builder(out).build();
        try {
            remapper.readInputs(in);
            remapper.apply(output);
        } finally {
            output.close();
            remapper.finish();
        }
    }

    private static IMappingProvider mappings(Path tiny, String srcNs, String dstNs) {
        return TinyUtils.createTinyMappingProvider(tiny, srcNs, dstNs);
    }

    private RemapOne() {
    }
}
