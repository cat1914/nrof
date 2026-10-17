import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/**
 * One-off build tool: patches teamreborn:energy:4.0.0 (built against MC 1.20.x)
 * so it runs on MC 1.21.1. The only incompatibility is its static
 * initializers calling {@code new Identifier(String)}, a constructor removed
 * in 1.21. Every occurrence of
 * {@code NEW Identifier; DUP; ...; INVOKESPECIAL Identifier.<init>(String)}
 * is rewritten to {@code ...; INVOKESTATIC Identifier.of(String)}.
 *
 * <p>Usage: {@code java PatchEnergyJar <in.jar> <out.jar>}</p>
 */
public final class PatchEnergyJar {

    /** Published energy jar is intermediary-mapped: Identifier = class_2960. */
    private static final String IDENTIFIER = "net/minecraft/class_2960";

    /** Intermediary name of {@code Identifier.of(String)} / mojmap {@code parse(String)}. */
    private static final String OF_METHOD = "method_60654";

    /** Intermediary name of {@code Identifier.of(ns, path)} / mojmap {@code fromNamespaceAndPath}. */
    private static final String OF2_METHOD = "method_60655";

    public static void main(String[] args) throws Exception {
        Path in = Path.of(args[0]);
        Path out = Path.of(args[1]);
        int[] patched = {0};
        Files.deleteIfExists(out);
        try (ZipFile zin = new ZipFile(in.toFile());
             ZipOutputStream zout = new ZipOutputStream(Files.newOutputStream(out))) {
            var entries = zin.entries();
            while (entries.hasMoreElements()) {
                ZipEntry e = entries.nextElement();
                if (e.isDirectory()) {
                    continue;
                }
                zout.putNextEntry(new ZipEntry(e.getName()));
                try (InputStream in2 = zin.getInputStream(e)) {
                    byte[] bytes = in2.readAllBytes();
                    if (e.getName().endsWith(".class")) {
                        ClassReader cr = new ClassReader(bytes);
                        ClassWriter cw = new ClassWriter(cr, 0);
                        cr.accept(new ClassVisitor(Opcodes.ASM9, cw) {
                            @Override
                            public MethodVisitor visitMethod(int access, String name, String desc,
                                                             String sig, String[] ex) {
                                return new IdentifierCtorRewriter(super.visitMethod(access, name, desc, sig, ex));
                            }
                        }, 0);
                        byte[] out2 = cw.toByteArray();
                        if (!java.util.Arrays.equals(bytes, out2)) {
                            patched[0]++;
                        }
                        zout.write(out2);
                    } else {
                        zout.write(bytes);
                    }
                }
                zout.closeEntry();
            }
        }
        System.out.println("patched " + patched[0] + " class(es) -> " + out);
    }

    /** Defers NEW/DUP on Identifier until the following instruction is known. */
    private static final class IdentifierCtorRewriter extends MethodVisitor {
        private int heldNew = 0; // 0 none, 1 NEW held, 2 NEW+DUP held

        IdentifierCtorRewriter(MethodVisitor mv) {
            super(Opcodes.ASM9, mv);
        }

        private void flush() {
            if (heldNew >= 1) {
                super.visitTypeInsn(Opcodes.NEW, IDENTIFIER);
            }
            if (heldNew >= 2) {
                super.visitInsn(Opcodes.DUP);
            }
            heldNew = 0;
        }

        @Override
        public void visitTypeInsn(int opcode, String type) {
            if (opcode == Opcodes.NEW && type.equals(IDENTIFIER) && heldNew == 0) {
                heldNew = 1;
                return;
            }
            flush();
            super.visitTypeInsn(opcode, type);
        }

        @Override
        public void visitInsn(int opcode) {
            if (opcode == Opcodes.DUP && heldNew == 1) {
                heldNew = 2;
                return;
            }
            flush();
            super.visitInsn(opcode);
        }

        @Override
        public void visitMethodInsn(int opcode, String owner, String name, String desc, boolean itf) {
            if (heldNew == 2 && opcode == Opcodes.INVOKESPECIAL && owner.equals(IDENTIFIER)
                    && name.equals("<init>")) {
                if (desc.equals("(Ljava/lang/String;)V")) {
                    heldNew = 0;
                    super.visitMethodInsn(Opcodes.INVOKESTATIC, IDENTIFIER, OF_METHOD,
                            "(Ljava/lang/String;)Lnet/minecraft/class_2960;", false);
                    return;
                }
                if (desc.equals("(Ljava/lang/String;Ljava/lang/String;)V")) {
                    heldNew = 0;
                    super.visitMethodInsn(Opcodes.INVOKESTATIC, IDENTIFIER, OF2_METHOD,
                            "(Ljava/lang/String;Ljava/lang/String;)Lnet/minecraft/class_2960;", false);
                    return;
                }
            }
            flush();
            super.visitMethodInsn(opcode, owner, name, desc, itf);
        }

        /** Constructor arguments (constants/locals/fields) do not break the pattern. */
        @Override
        public void visitLdcInsn(Object value) {
            super.visitLdcInsn(value);
        }

        @Override
        public void visitVarInsn(int opcode, int var) {
            super.visitVarInsn(opcode, var);
        }

        @Override
        public void visitFieldInsn(int opcode, String owner, String name, String desc) {
            super.visitFieldInsn(opcode, owner, name, desc);
        }

        @Override
        public void visitEnd() {
            flush();
            super.visitEnd();
        }
    }
}
