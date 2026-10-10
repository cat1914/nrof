import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.commons.ClassRemapper;
import org.objectweb.asm.commons.Remapper;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.GZIPInputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/**
 * One-off build tool: remaps the NeoForge API module jar with a composed
 * tiny-v2 mapping (mojmap -> intermediary) using a flat lookup table.
 * Mirrors the runtime TableRemapper logic without the NROF dependencies.
 *
 * <p>Usage: {@code java RemapTable.java <in.jar> <out.jar> <tiny.gz>}</p>
 */
public final class RemapTable {

    public static void main(String[] args) throws IOException {
        Path in = Path.of(args[0]);
        Path out = Path.of(args[1]);
        Map<String, String> classes = new HashMap<>();
        Map<String, String> fields = new HashMap<>();
        Map<String, String> methods = new HashMap<>();

        try (InputStream raw = Files.newInputStream(Path.of(args[2]));
             GZIPInputStream gz = new GZIPInputStream(raw, 1 << 16)) {
            java.io.BufferedReader reader = new java.io.BufferedReader(
                    new java.io.InputStreamReader(gz, StandardCharsets.UTF_8), 1 << 16);
            String current = null;
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isEmpty() || line.startsWith("tiny\t") || line.startsWith("#")) {
                    continue;
                }
                if (!line.startsWith("\t")) {
                    if (!line.startsWith("c\t")) {
                        continue;
                    }
                    String[] parts = line.split("\t");
                    if (parts.length >= 3) {
                        current = parts[1];
                        classes.put(parts[1], parts[2]);
                    }
                    continue;
                }
                String[] parts = line.substring(1).split("\t");
                if (current == null || parts.length < 4) {
                    continue;
                }
                String key = current + '.' + parts[2] + ' ' + parts[1];
                if (parts[0].equals("f")) {
                    fields.put(key, parts[3]);
                } else if (parts[0].equals("m")) {
                    methods.put(key, parts[3]);
                }
            }
        }
        System.out.println("loaded " + classes.size() + " classes, "
                + fields.size() + " fields, " + methods.size() + " methods");

        Remapper remapper = new Remapper() {
            @Override
            public String map(String internalName) {
                return classes.getOrDefault(internalName, internalName);
            }

            @Override
            public String mapFieldName(String owner, String name, String descriptor) {
                return fields.getOrDefault(owner + '.' + name + ' ' + descriptor, name);
            }

            @Override
            public String mapMethodName(String owner, String name, String descriptor) {
                if (name.startsWith("<")) {
                    return name;
                }
                return methods.getOrDefault(owner + '.' + name + ' ' + descriptor, name);
            }

            @Override
            public String mapRecordComponentName(String owner, String name, String descriptor) {
                return mapFieldName(owner, name, descriptor);
            }
        };

        try (ZipFile zin = new ZipFile(in.toFile());
             ZipOutputStream zout = new ZipOutputStream(Files.newOutputStream(out))) {
            var entries = zin.entries();
            while (entries.hasMoreElements()) {
                ZipEntry e = entries.nextElement();
                if (e.isDirectory()) {
                    continue;
                }
                String name = e.getName();
                if (name.endsWith(".SF") || name.endsWith(".RSA") || name.endsWith(".DSA")) {
                    continue;
                }
                zout.putNextEntry(new ZipEntry(name));
                if (name.endsWith(".class") && !name.equals("module-info.class")) {
                    ClassReader cr = new ClassReader(zin.getInputStream(e).readAllBytes());
                    ClassWriter cw = new ClassWriter(cr, 0);
                    cr.accept(new ClassRemapper(cw, remapper), 0);
                    zout.write(cw.toByteArray());
                } else {
                    zin.getInputStream(e).transferTo(zout);
                }
                zout.closeEntry();
            }
        }
        System.out.println("remapped " + in + " -> " + out);
    }
}
