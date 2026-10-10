import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.GZIPOutputStream;

/**
 * One-off build tool: converts Mojang's proguard client mappings (client.txt)
 * into a gzipped tiny-v2 file with namespaces [mojmap, official].
 *
 * <p>The output is bundled into the NROF jar and used at runtime by the
 * pre-build stage to remap NeoForge mod jars (compiled against mojmap) to
 * Fabric's runtime namespace.</p>
 *
 * <p>Usage: {@code java MojmapToTiny.java <client.txt> <out.tiny.gz>}</p>
 */
public final class MojmapToTiny {

    private static final Pattern LEADING_RANGES = Pattern.compile("^(\\d+:\\d+:)+");
    private static final Pattern TRAILING_RANGES = Pattern.compile("(:\\d+)+$");
    private static final Pattern METHOD = Pattern.compile("^(.*?)\\s+([\\w$<>]+)\\((.*)\\)$");
    private static final Pattern FIELD = Pattern.compile("^(.*?)\\s+([\\w$]+)$");

    public static void main(String[] args) throws IOException {
        Path in = Path.of(args[0]);
        Path out = Path.of(args[1]);
        Files.createDirectories(out.getParent());

        Set<String> emitted = new HashSet<>();
        try (BufferedReader r = Files.newBufferedReader(in, StandardCharsets.UTF_8);
             Writer w = new BufferedWriter(new OutputStreamWriter(
                     new GZIPOutputStream(Files.newOutputStream(out), 1 << 16), StandardCharsets.UTF_8), 1 << 16)) {
            w.write("tiny\t2\t0\tmojmap\tofficial\n");

            String current = null; // "mojmapClass\toffClass"
            String line;
            while ((line = r.readLine()) != null) {
                String t = line.strip();
                if (t.isEmpty() || t.startsWith("#")) {
                    continue;
                }
                if (t.endsWith(":") && t.contains(" -> ")) {
                    int arrow = t.indexOf(" -> ");
                    String left = t.substring(0, arrow).strip();
                    String right = t.substring(arrow + 4).strip();
                    if (!left.contains("(") && right.endsWith(":")) {
                        String moj = left.replace('.', '/');
                        String obf = right.substring(0, right.length() - 1).replace('.', '/');
                        current = moj + "\t" + obf;
                        w.write("c\t" + current + "\n");
                        continue;
                    }
                }
                if (current == null) {
                    continue; // member before any class — impossible, but be safe
                }
                String decl = LEADING_RANGES.matcher(t).replaceFirst("").strip();
                int arrow = decl.indexOf(" -> ");
                if (arrow < 0) {
                    continue;
                }
                String member = decl.substring(0, arrow).strip();
                String obfName = TRAILING_RANGES.matcher(decl.substring(arrow + 4).strip()).replaceFirst("").strip();

                Matcher mm = METHOD.matcher(member);
                if (mm.matches()) {
                    String name = mm.group(2);
                    String desc = "(" + argsDesc(mm.group(3)) + ")" + typeDesc(mm.group(1).strip());
                    emit(w, emitted, current, "method", desc, name, obfName);
                } else {
                    Matcher fm = FIELD.matcher(member);
                    if (fm.matches()) {
                        String name = fm.group(2);
                        String desc = typeDesc(fm.group(1).strip());
                        emit(w, emitted, current, "field", desc, name, obfName);
                    }
                }
            }
        }
        System.out.println("written " + out);
    }

    private static void emit(Writer w, Set<String> emitted, String cls, String kind,
                             String desc, String name, String obf) throws IOException {
        String key = cls + "|" + kind + "|" + desc + "|" + name + "|" + obf;
        if (!emitted.add(key)) {
            return;
        }
        // tiny v2 member row tags are single characters: f = field, m = method.
        String tag = switch (kind) {
            case "method" -> "m";
            case "field" -> "f";
            default -> kind;
        };
        w.write("\t" + tag + "\t" + desc + "\t" + name + "\t" + obf + "\n");
    }

    private static String argsDesc(String args) {
        if (args.isBlank()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (String a : args.split(",")) {
            sb.append(typeDesc(a.strip()));
        }
        return sb.toString();
    }

    private static String typeDesc(String type) {
        int dims = 0;
        String t = type;
        while (t.endsWith("[]")) {
            dims++;
            t = t.substring(0, t.length() - 2).strip();
        }
        String base = switch (t) {
            case "void" -> "V";
            case "int" -> "I";
            case "boolean" -> "Z";
            case "byte" -> "B";
            case "char" -> "C";
            case "short" -> "S";
            case "long" -> "J";
            case "float" -> "F";
            case "double" -> "D";
            default -> "L" + t.replace('.', '/') + ";";
        };
        return "[".repeat(dims) + base;
    }

    private MojmapToTiny() {
    }
}
