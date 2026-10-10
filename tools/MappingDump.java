import net.fabricmc.tinyremapper.IMappingProvider;
import net.fabricmc.tinyremapper.TinyUtils;
import net.fabricmc.tinyremapper.api.TrClass;

import java.nio.file.Path;

/** Dumps mapping entries for a given class prefix from a tiny file. */
public final class MappingDump {

    public static void main(String[] args) {
        Path tiny = Path.of(args[0]);
        String srcNs = args[1];
        String dstNs = args[2];
        String filter = args[3];

        1   1IMappingProvider provider = TinyUtils.createTinyMappingProvider(tiny, srcNs, dstNs);
        provider.load(new IMappingProvider.MappingAcceptor() {
            @Override
            public void acceptClass(String src, String dst) {
                if (src.contains(filter)) {
                    System.out.println("CLASS  " + src + " -> " + dst);
                }
            }

            @Override
            public void acceptField(IMappingProvider.Member field, String dst) {
                if (field.owner.contains(filter)) {
                    System.out.println("FIELD  " + field.owner + "." + field.name + " " + field.desc + " -> " + dst);
                }
            }

            @Override
            public void acceptMethod(IMappingProvider.Member method, String dst) {
                if (method.owner.contains(filter)) {
                    System.out.println("METHOD " + method.owner + "." + method.name + " " + method.desc + " -> " + dst);
                }
            }

            @Override
            public void acceptMethodArg(IMappingProvider.Member method, int lvIndex, String dst) {
            }

            @Override
            public void acceptMethodVar(IMappingProvider.Member method, int lvIndex, int startOp, int asmIndex, String dst) {
            }
        });
    }
}
