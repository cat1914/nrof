import com.cat.nrof.transform.TableRemapper;

import java.nio.file.Path;

/**
 * One-off runner: re-generates libs/neoforge-api-*-intermediary.jar using the
 * current composed mojmap->intermediary mapping and the inheritance-aware
 * TableRemapper (the same remapper the runtime pipeline uses for mod jars).
 *
 * <p>Replaces the stale two-pass tiny-remapper output produced by RemapOne,
 * which predated the fixed composed mapping and left calls like
 * {@code ResourceKey.create} unmapped (NoSuchMethodError at runtime).</p>
 *
 * <p>Usage: {@code java -cp <main-classes;asm> RunTableRemap <in.jar> <out.jar> <composed.tiny.gz>}</p>
 */
public final class RunTableRemap {

    public static void main(String[] args) throws Exception {
        Path in = Path.of(args[0]);
        Path out = Path.of(args[1]);
        Path tinyGz = Path.of(args[2]);
        TableRemapper remapper = TableRemapper.loadTinyGz(tinyGz);
        java.nio.file.Files.deleteIfExists(out);
        remapper.remapJar(in, out);
        System.out.println("remapped " + in + " -> " + out);
    }
}
