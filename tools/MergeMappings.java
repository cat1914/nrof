import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * Composes two tiny-v2 mapping files into a single direct mapping:
 * mojmap -> official (Mojang proguard conversion) + official -> intermediary
 * (Fabric) = mojmap -> intermediary.
 *
 * <p>NeoForge mods are compiled against mojmap, Fabric's runtime speaks
 * intermediary. Remapping directly (instead of two passes through the official
 * namespace) avoids tiny-remapper's conflict propagation, which cannot handle
 * the name swaps that are ubiquitous in obfuscated mappings.</p>
 *
 * <p>The output is hierarchical tiny-v2: every member row directly follows its
 * class row. Table-remappers parse member rows relative to the preceding class
 * row, so a flat "all classes, then all members" layout would attach every
 * member to the last class emitted.</p>
 *
 * <p>Usage: {@code java MergeMappings.java <mojmap-official.tiny.gz> <intermediary-official.tiny.gz> <out.tiny.gz>}</p>
 * Both inputs must be gzipped tiny-v2 with the src namespace first
 * (mojmap-official: [mojmap, official]; intermediary-official: [official, intermediary]).
 */
public final class MergeMappings {

    public static void main(String[] args) throws IOException {
        Path mojTiny = Path.of(args[0]);
        Path interTiny = Path.of(args[1]);
        Path out = Path.of(args[2]);

        // ---- pass 1: mojmap -> official ------------------------------------
        Map<String, String> cls1 = new LinkedHashMap<>();      // mojmap class -> official class
        Map<String, String> fld1 = new HashMap<>();            // mojmap cls . name SP desc -> official name
        Map<String, String> mth1 = new HashMap<>();
        read(mojTiny, false, cls1, fld1, mth1);

        // ---- pass 2: official -> intermediary --------------------------------
        // (the bundled tiny's header is [official, intermediary] — no inversion)
        Map<String, String> cls2 = new HashMap<>();            // official class -> intermediary class
        Map<String, String> fld2 = new HashMap<>();            // official cls . name SP desc -> intermediary name
        Map<String, String> mth2 = new HashMap<>();
        read(interTiny, false, cls2, fld2, mth2);

        // ---- compose into hierarchical blocks -------------------------------
        // tiny v2 is hierarchical: member rows must directly follow their class
        // row, so group members per class before emitting.
        Map<String, StringBuilder> blocks = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : cls1.entrySet()) {
            String inter = cls2.get(e.getValue());
            if (inter != null) {
                blocks.put(e.getKey(), new StringBuilder("c\t" + e.getKey() + "\t" + inter + "\n"));
            }
        }

        int fields = 0;
        int methods = 0;
        String searchProbe = null;
        for (Map.Entry<String, String> f : fld1.entrySet()) {
            String[] parsed = splitKey(f.getKey());
            if (parsed == null) {
                continue;
            }
            String mojCls = parsed[0];
            String name = parsed[1];
            String mojDesc = parsed[2];
            StringBuilder block = blocks.get(mojCls);
            if (block == null) {
                continue;
            }
            String off = cls1.get(mojCls);
            String offDesc = remapDesc(mojDesc, cls1);
            String interName = fld2.get(off + '.' + f.getValue() + ' ' + offDesc);
            if (interName == null) {
                continue;
            }
            block.append("\tf\t").append(mojDesc).append('\t').append(name).append('\t').append(interName).append('\n');
            fields++;
            if (name.equals("SEARCH") && mojCls.endsWith("CreativeModeTabs")) {
                searchProbe = mojCls + ".SEARCH " + mojDesc + " -> " + interName;
            }
        }

        for (Map.Entry<String, String> m : mth1.entrySet()) {
            String[] parsed = splitKey(m.getKey());
            if (parsed == null) {
                continue;
            }
            String mojCls = parsed[0];
            String name = parsed[1];
            String mojDesc = parsed[2];
            StringBuilder block = blocks.get(mojCls);
            if (block == null) {
                continue;
            }
            String off = cls1.get(mojCls);
            String interName = mth2.get(off + '.' + m.getValue() + ' ' + remapDesc(mojDesc, cls1));
            if (interName == null) {
                continue;
            }
            block.append("\tm\t").append(mojDesc).append('\t').append(name).append('\t').append(interName).append('\n');
            methods++;
        }

        try (Writer w = new BufferedWriter(new OutputStreamWriter(
                new GZIPOutputStream(Files.newOutputStream(out), 1 << 16), StandardCharsets.UTF_8), 1 << 16)) {
            w.write("tiny\t2\t0\tmojmap\tintermediary\n");
            for (StringBuilder block : blocks.values()) {
                w.write(block.toString());
            }
        }
        System.out.println("composed " + out + ": " + blocks.size() + " classes, "
                + fields + " fields, " + methods + " methods");
        if (searchProbe != null) {
            System.out.println("probe: " + searchProbe);
        } else {
            System.out.println("probe: CreativeModeTabs.SEARCH NOT FOUND");
        }
    }

    /** Key layout: "<cls>.<name> <desc>" → {cls, name, desc}; member names never contain dots/spaces. */
    private static String[] splitKey(String key) {
        int sp = key.lastIndexOf(' ');
        if (sp < 0) {
            return null;
        }
        int dot = key.lastIndexOf('.', sp);
        if (dot < 0) {
            return null;
        }
        return new String[] {key.substring(0, dot), key.substring(dot + 1, sp), key.substring(sp + 1)};
    }

    /**
     * Parses a gzipped tiny-v2 file into flat maps. Member keys:
     * "<cls>.<name> <desc>". With {@code invert}, src and dst columns are
     * swapped (used to read intermediary-official as official->intermediary).
     */
    private static void read(Path tiny, boolean invert, Map<String, String> cls,
                             Map<String, String> fld, Map<String, String> mth) throws IOException {
        try (BufferedReader r = new BufferedReader(new InputStreamReader(
                new GZIPInputStream(Files.newInputStream(tiny), 1 << 16), StandardCharsets.UTF_8), 1 << 16)) {
            String current = null;
            String line;
            while ((line = r.readLine()) != null) {
                if (line.isEmpty() || line.startsWith("tiny\t") || line.startsWith("#")) {
                    continue;
                }
                if (!line.startsWith("\t")) {
                    if (!line.startsWith("c\t")) {
                        continue;
                    }
                    String[] parts = line.split("\t");
                    if (parts.length >= 3) {
                        current = invert ? parts[2] : parts[1];
                        String dst = invert ? parts[1] : parts[2];
                        cls.put(current, dst);
                    }
                    continue;
                }
                // member row: \t f|m \t desc \t src \t dst
                String[] parts = line.substring(1).split("\t");
                if (current == null || parts.length < 4) {
                    continue;
                }
                String src = invert ? parts[3] : parts[2];
                String dst = invert ? parts[2] : parts[3];
                String key = current + '.' + src + ' ' + parts[1];
                if (parts[0].equals("f")) {
                    fld.put(key, dst);
                } else if (parts[0].equals("m")) {
                    mth.put(key, dst);
                }
            }
        }
    }

    /** Replaces mojmap class names inside a type/method descriptor with their official names. */
    private static String remapDesc(String desc, Map<String, String> cls) {
        if (!desc.contains("L")) {
            return desc;
        }
        StringBuilder sb = new StringBuilder(desc.length());
        int i = 0;
        while (i < desc.length()) {
            char c = desc.charAt(i);
            if (c == 'L') {
                int end = desc.indexOf(';', i);
                if (end < 0) {
                    sb.append(desc, i, desc.length());
                    break;
                }
                String internal = desc.substring(i + 1, end);
                String mapped = cls.get(internal);
                sb.append('L').append(mapped != null ? mapped : internal).append(';');
                i = end + 1;
            } else {
                sb.append(c);
                i++;
            }
        }
        return sb.toString();
    }
}
