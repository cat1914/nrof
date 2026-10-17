import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.nio.file.Path;
import java.util.zip.ZipFile;

/** Scans a jar for INVOKE and NEW references to net/minecraft/class_2960 (Identifier). */
public final class ProbeIdentifierRefs {

    public static void main(String[] args) throws Exception {
        try (ZipFile zin = new ZipFile(Path.of(args[0]).toFile())) {
            var entries = zin.entries();
            while (entries.hasMoreElements()) {
                var e = entries.nextElement();
                if (e.isDirectory() || !e.getName().endsWith(".class")) {
                    continue;
                }
                byte[] bytes = zin.getInputStream(e).readAllBytes();
                String raw = new String(bytes, java.nio.charset.StandardCharsets.ISO_8859_1);
                if (!raw.contains("class_2960")) {
                    continue;
                }
                ClassReader cr = new ClassReader(bytes);
                cr.accept(new ClassVisitor(Opcodes.ASM9) {
                    @Override
                    public MethodVisitor visitMethod(int access, String name, String desc, String sig, String[] ex) {
                        return new MethodVisitor(Opcodes.ASM9) {
                            @Override
                            public void visitMethodInsn(int opcode, String owner, String m, String d, boolean itf) {
                                if (owner.equals("net/minecraft/class_2960")) {
                                    System.out.println(e.getName() + " :: " + name + desc
                                            + "  ->  " + insn(opcode) + " " + owner + "." + m + d);
                                }
                            }
                            @Override
                            public void visitTypeInsn(int opcode, String type) {
                                if (type.equals("net/minecraft/class_2960")) {
                                    System.out.println(e.getName() + " :: " + name + desc
                                            + "  ->  TYPE(" + opcode + ") " + type);
                                }
                            }
                        };
                    }
                }, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
            }
        }
    }

    private static String insn(int opcode) {
        return switch (opcode) {
            case Opcodes.INVOKEVIRTUAL -> "INVOKEVIRTUAL";
            case Opcodes.INVOKESPECIAL -> "INVOKESPECIAL";
            case Opcodes.INVOKESTATIC -> "INVOKESTATIC";
            case Opcodes.INVOKEINTERFACE -> "INVOKEINTERFACE";
            default -> "op" + opcode;
        };
    }
}
