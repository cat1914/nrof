import net.fabricmc.mappingio.MappingReader;
import net.fabricmc.mappingio.tree.MemoryMappingTree;

import java.nio.file.Path;

/** Counts what mapping-io actually parses out of a tiny file. */
public final class MappingDump2 {

    public static void main(String[] args) throws Exception {
        Path tiny = Path.of(args[0]);

        MemoryMappingTree tree = new MemoryMappingTree();
        MappingReader.read(tiny, tree);

        System.out.println("format      : " + MappingReader.detectFormat(tiny));
        System.out.println("src ns      : " + tree.getSrcNamespace());
        System.out.println("dst ns      : " + tree.getDstNamespaces());
        System.out.println("classes     : " + tree.getClasses().size());
        System.out.println("fields      : " + tree.getClasses().stream().mapToInt(c -> c.getFields().size()).sum());
        System.out.println("methods     : " + tree.getClasses().stream().mapToInt(c -> c.getMethods().size()).sum());

        var cls = tree.getClass("net/minecraft/world/item/CreativeModeTabs");
        if (cls != null) {
            System.out.println("CreativeModeTabs official name: " + cls.getName("official"));
            cls.getFields().stream()
                    .filter(f -> f.getName(0).equals("SEARCH") || f.getName(0).equals("BUILDING_BLOCKS"))
                    .forEach(f -> System.out.println("  field " + f.getName(0) + " " + f.getDesc(0)
                            + " -> official: " + f.getName("official")));
            cls.getMethods().stream().limit(3)
                    .forEach(m -> System.out.println("  method " + m.getName(0) + " -> official: " + m.getName("official")));
        } else {
            System.out.println("CreativeModeTabs NOT FOUND in tree");
        }
    }
}
