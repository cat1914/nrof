import net.fabricmc.tinyremapper.IMappingProvider;
import net.fabricmc.tinyremapper.OutputConsumerPath;
import net.fabricmc.tinyremapper.TinyRemapper;
import net.fabricmc.tinyremapper.TinyUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

/**
 * Experiment: same as RemapOne but feeds an environment jar to
 * {@code readClassPath} so member references to classes outside the input
 * (e.g. Minecraft's {@code CreativeModeTabs.SEARCH}) can be resolved by
 * tiny-remapper's field mapping pass.
 *
 * <p>Usage: {@code java RemapOneCP <in.jar> <out.jar> <tiny[.gz]> <srcNs> <dstNs> [<env.jar>...]}</p>
 */
public final class RemapOneCP {

    public static void main(String[] args) throws IOException {
        Path in = Path.of(args[0]);
        Path out = Path.of(args[1]);
        Path tiny = gunzip(Path.of(args[2]));
        String srcNs = args[3];
        String dstNs = args[4];
        Path[] env = Arrays.stream(args).skip(5).map(Path::of).toArray(Path[]::new);

        TinyRemapper remapper = TinyRemapper.newRemapper()
                .withMappings(TinyUtils.createTinyMappingProvider(tiny, srcNs, dstNs))
                .ignoreConflicts(true)
                .build();
        if (env.length > 0) {
            remapper.readClassPathAsync(env);
        }
        OutputConsumerPath output = new OutputConsumerPath.Builder(out).build();
        try {
            remapper.readInputs(in);
            remapper.apply(output);
        } finally {
            output.close();
            remapper.finish();
        }
        System.out.println("remapped " + in + " -> " + out + " (env jars: " + env.length + ")");
    }

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
}
