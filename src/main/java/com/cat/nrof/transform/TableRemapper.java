package com.cat.nrof.transform;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.commons.ClassRemapper;
import org.objectweb.asm.commons.Remapper;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.zip.GZIPInputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/**
 * Direct table-driven remapper: applies a composed tiny-v2 mapping
 * (mojmap -> intermediary) to a jar in a single pass.
 *
 * <p>Member lookups are performed with inheritance-chain fallback: if a
 * field/method key is not found against the exact owner, the lookup walks
 * up the owner's super/interfaces (from this jar's own class pool and the
 * Minecraft runtime's common class hierarchy). Without this, {@code this.minecraft}
 * inside a subclass of {@code Screen} isn't matched, because the mapping table
 * stores the field under the owner that declared it (Screen), not under the
 * subclass that merely inherited and accessed it.</p>
 */
public final class TableRemapper {

    private final Map<String, String> classes = new HashMap<>();
    private final Map<String, String> fields = new HashMap<>();
    private final Map<String, String> methods = new HashMap<>();

    /** class internal name -> super + direct interfaces (post-class-load, if available). */
    private final Map<String, String[]> hierarchy = new HashMap<>();

    /** cache of: "subOwner/member+desc" -> resolved superOwner that owns the mapping. */
    private final Map<String, String> fieldOwnerCache = new HashMap<>();
    private final Map<String, String> methodOwnerCache = new HashMap<>();

    private TableRemapper() {
    }

    public static TableRemapper loadTinyGz(Path gz) throws IOException {
        TableRemapper t = new TableRemapper();
        try (InputStream raw = Files.newInputStream(gz);
             GZIPInputStream in = new GZIPInputStream(raw, 1 << 16)) {
            t.read(in);
        }
        return t;
    }

    public static TableRemapper loadTinyGz(InputStream in) throws IOException {
        TableRemapper t = new TableRemapper();
        try (GZIPInputStream gunzip = new GZIPInputStream(in, 1 << 16)) {
            t.read(gunzip);
        }
        return t;
    }

    private void read(InputStream in) throws IOException {
        BufferedReader reader = new BufferedReader(
                new InputStreamReader(in, StandardCharsets.UTF_8), 1 << 16);
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

    // ---- jar remapping ------------------------------------------------------

    public void remapJar(Path in, Path out) throws IOException {
        // Phase 1: scan the input jar and populate the hierarchy map so member
        // lookups can walk up the inheritance chain declared in this jar.
        try (ZipFile zin = new ZipFile(in.toFile())) {
            var entries = zin.entries();
            while (entries.hasMoreElements()) {
                ZipEntry e = entries.nextElement();
                String name = e.getName();
                if (!name.endsWith(".class") || name.equals("module-info.class") || e.isDirectory()) {
                    continue;
                }
                try {
                    addClassHierarchy(zin.getInputStream(e).readAllBytes());
                } catch (Throwable ignore) {
                    // a single unreadable class shouldn't abort the entire scan
                }
            }
        }

        // Phase 2: remap every class.
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
                    byte[] remapped = remapClass(zin.getInputStream(e).readAllBytes());
                    zout.write(remapped);
                } else {
                    zin.getInputStream(e).transferTo(zout);
                }
                zout.closeEntry();
            }
        }
    }

    /** Scans one class's bytes and records its super/interfaces in the hierarchy map. */
    private void addClassHierarchy(byte[] bytes) {
        try {
            ClassReader cr = new ClassReader(bytes);
            // visit(Opcodes.ASM9, ...) — the no-arg accepts() we use for remapping
            // exposes superName/interfaces via the header too; use a lightweight
            // ClassVisitor that only records the top-level inheritance info.
            cr.accept(new org.objectweb.asm.ClassVisitor(Opcodes.ASM9) {
                private String thisName;
                @Override
                public void visit(int version, int access, String name, String signature,
                                  String superName, String[] interfaces) {
                    thisName = name;
                    int n = (superName == null ? 0 : 1) + interfaces.length;
                    if (n == 0) {
                        hierarchy.put(name, new String[0]);
                        return;
                    }
                    String[] up = new String[n];
                    int idx = 0;
                    if (superName != null) up[idx++] = superName;
                    System.arraycopy(interfaces, 0, up, idx, interfaces.length);
                    hierarchy.put(name, up);
                }
            }, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        } catch (Throwable ignore) { /* skip malformed */ }
    }

    private byte[] remapClass(byte[] bytes) {
        ClassReader cr = new ClassReader(bytes);
        ClassWriter cw = new ClassWriter(cr, 0);
        cr.accept(new ClassRemapper(cw, new TableRemapperImpl()), 0);
        return cw.toByteArray();
    }

    // ---- member lookup with inheritance fallback ----------------------------

    /**
     * Returns the most-derived owner-class whose direct mapping table contains
     * {@code owner.name desc} for a field, or {@code null} when none does.
     */
    private String resolveFieldOwner(String owner, String name, String desc) {
        String cacheKey = owner + '.' + name + ' ' + desc;
        String cached = fieldOwnerCache.get(cacheKey);
        if (cached != null) {
            return cached.isEmpty() ? null : cached;
        }
        String resolved = walkHierarchy(owner, o -> fields.containsKey(o + '.' + name + ' ' + desc));
        fieldOwnerCache.put(cacheKey, resolved == null ? "" : resolved);
        return resolved;
    }

    private String resolveMethodOwner(String owner, String name, String desc) {
        String cacheKey = owner + '.' + name + ' ' + desc;
        String cached = methodOwnerCache.get(cacheKey);
        if (cached != null) {
            return cached.isEmpty() ? null : cached;
        }
        String resolved = walkHierarchy(owner, o -> methods.containsKey(o + '.' + name + ' ' + desc));
        methodOwnerCache.put(cacheKey, resolved == null ? "" : resolved);
        return resolved;
    }

    private interface HierarchyPredicate { boolean test(String o); }

    private String walkHierarchy(String start, HierarchyPredicate pred) {
        if (pred.test(start)) return start;
        Set<String> visited = new HashSet<>();
        Queue<String> q = new ArrayDeque<>();
        visited.add(start);
        enqueueSupers(q, visited, start);
        while (!q.isEmpty()) {
            String o = q.poll();
            if (pred.test(o)) return o;
            enqueueSupers(q, visited, o);
        }
        return null;
    }

    private void enqueueSupers(Queue<String> q, Set<String> visited, String o) {
        String[] up = hierarchy.get(o);
        if (up == null) {
            // No bytecode-level super info — try the class→intermediary mapping;
            // Minecraft runtime classes we don't own also declare inherited
            // members (e.g. Screen.minecraft). The mapping table uses mojmap
            // owners; try known "obvious" ancestors via the exact name the
            // mapping table knows for them. That list is seeded in seedKnownSupers().
            up = KNOWN_SUPERS.get(o);
        }
        if (up != null) {
            for (String p : up) {
                if (visited.add(p)) q.add(p);
            }
        }
    }

    private class TableRemapperImpl extends Remapper {
        @Override
        public String map(String internalName) {
            return classes.getOrDefault(internalName, internalName);
        }

        @Override
        public String mapFieldName(String owner, String name, String descriptor) {
            String resolvedOwner = resolveFieldOwner(owner, name, descriptor);
            if (resolvedOwner == null) return name;
            return fields.get(resolvedOwner + '.' + name + ' ' + descriptor);
        }

        @Override
        public String mapMethodName(String owner, String name, String descriptor) {
            if (name.startsWith("<")) return name; // <init> / <clinit>
            String resolvedOwner = resolveMethodOwner(owner, name, descriptor);
            if (resolvedOwner == null) return name;
            return methods.get(resolvedOwner + '.' + name + ' ' + descriptor);
        }

        @Override
        public String mapRecordComponentName(String owner, String name, String descriptor) {
            return mapFieldName(owner, name, descriptor);
        }
    }

    // ---- minimal hand-rolled "super classes the mapping table references" ----
    // These are common Minecraft/FML ancestors whose member mappings are
    // indexed under the ancestor name, but whose bytecode isn't in the remapped
    // jar (they ship with Minecraft/FML). The hierarchy map extracted from the
    // jar never sees them, so inheritance walk-up stops short without this.
    // Build with: run KNOWN_SUPERS_GEN below and paste output.
    private static final Map<String, String[]> KNOWN_SUPERS;
    static {
        Map<String, String[]> m = new HashMap<>();
        // AbstractButton / AbstractWidget / Button are the usual widget roots;
        // Screen is the Gui ancestor (ToolSelectionScreen extends some Screen).
        putSuper(m, "net/minecraft/client/gui/screens/Screen",
                 "net/minecraft/client/gui/components/events/GuiEventListener",
                 "net/minecraft/client/gui/Renderable");
        putSuper(m, "net/minecraft/client/gui/components/AbstractContainerEventHandler",
                 "net/minecraft/client/gui/screens/Screen");
        putSuper(m, "net/minecraft/client/gui/screens/AbstractContainerEventHandler",
                 "net/minecraft/client/gui/screens/Screen");
        putSuper(m, "net/minecraft/client/gui/components/AbstractWidget",
                 "net/minecraft/client/gui/screens/Screen");
        // ContainerMenu / AbstractContainerMenu / BaseContainerScreen — inherited widget fields
        putSuper(m, "net/minecraft/client/gui/screens/MenuScreen",
                 "net/minecraft/client/gui/screens/Screen");
        putSuper(m, "net/minecraft/client/gui/screens/inventory/InventoryMenuScreen",
                 "net/minecraft/client/gui/screens/Screen");
        // Registration roots — DeferredRegister, Items/Blocks holder fields
        // (rarely accessed by subclass directly, but cheap to have).
        // FML common parent classes.
        putSuper(m, "net/neoforged/fml/common/ModContainer", (String[])null);
        putSuper(m, "net/neoforged/bus/api/IEventBus", (String[])null);
        // Block/Item/Entity/BER — common hierarchy entry points that mods often subclass
        putSuper(m, "net/minecraft/world/level/block/Block",
                 "net/minecraft/world/level/block/state/BlockBehaviour");
        putSuper(m, "net/minecraft/world/item/Item",
                 "net/minecraft/world/item/ItemLike");
        putSuper(m, "net/minecraft/world/entity/Entity", (String[])null);
        putSuper(m, "net/minecraft/world/entity/LivingEntity",
                 "net/minecraft/world/entity/Entity");
        putSuper(m, "net/minecraft/world/entity/player/Player",
                 "net/minecraft/world/entity/LivingEntity");
        putSuper(m, "net/minecraft/client/Minecraft", (String[])null);
        // Holder hierarchy: many registry/registrate helpers invoke value() on a
        // Holder$Reference or Holder$Direct receive type directly (invokevirtual
        // on the concrete type), but the method mapping lives under the
        // interface Holder because the interface is what declared it. Without
        // linking Reference/Direct → Holder, the inheritance walk stops and
        // mapMethodName returns the unmapped "value", producing:
        //     NoSuchMethodError: class_6880$class_6883.value()Ljava/lang/Object;
        putSuper(m, "net/minecraft/core/Holder", (String[])null);
        putSuper(m, "net/minecraft/core/Holder$Reference",
                 "net/minecraft/core/Holder");
        putSuper(m, "net/minecraft/core/Holder$Direct",
                 "net/minecraft/core/Holder");
        // Registrate roots
        putSuper(m, "com/tterrag/registrate/AbstractRegistrate", (String[])null);
        putSuper(m, "com/tterrag/registrate/Registrate",
                 "com/tterrag/registrate/AbstractRegistrate");
        KNOWN_SUPERS = m;
    }
    @SafeVarargs
    private static void putSuper(Map<String, String[]> m, String key, String... parents) {
        m.put(key, parents == null ? new String[0] : parents);
    }

    /** Helper to confirm a given hierarchy entry (useful for tests/debugging). */
    public static String[] knownSupersFor(String owner) {
        String[] direct = KNOWN_SUPERS.get(owner);
        return direct == null ? new String[0] : Arrays.copyOf(direct, direct.length);
    }
}
