package com.cat.nrof;

/** Central version constants for NROF. */
public final class NrofVersion {
    public static final String VERSION = "1.0-SNAPSHOT";
    /**
     * v2: cache entries now also contain the pre-build remapped jar
     * (mojmap -> official -> intermediary) produced by ModRemapper.
     * v3: cache entries additionally extract and remap nested JiJ jars
     * (META-INF/jars/*, e.g. Registrate/Ponder inside create.jar) into
     * nested-jars/ so they can be mounted onto the knot classloader.
     * v4: nested jar extraction also covers the NeoForge JarJar flavor
     * (META-INF/jarjar/*) which is what real NeoForge mods actually embed.
     * v5: mojmap tiny regenerated with correct tiny-v2 member tags (f/m) —
     * the previous file silently carried zero field/method mappings, so every
     * remapped jar must be rebuilt from source.
     * v6: composed mojmap->intermediary tiny regenerated in hierarchical
     * layout (member rows directly follow their class row). The previous flat
     * layout attached every member to the last emitted class, so remapped
     * jars kept unmapped field/method references (e.g. CreativeModeTabs.SEARCH).
     * v7: TableRemapper now resolves inherited field/method references by
     * walking the declaring-class chain up through super/interfaces. Without
     * this, `this.minecraft` on a subclass of Screen didn't remap because
     * the mapping stores that field under Screen, not the subclass owner.
     * v8: KNOWN_SUPERS seed extended with Holder$Reference/Direct → Holder
     * to resolve DeferredHolder.value() invoked on the concrete nested type
     * (invokevirtual on Holder$Reference produced NoSuchMethodError because
     * the mapping was only indexed under the interface Holder itself).
     * v9: FabricMetadataBuilder maps FFAPI module dependencies (fabric_api,
     * fabric_*_vN) onto the real hyphenated Fabric API module ids with a "*"
     * version range — converted metadata must be regenerated so the Fabric
     * index resolves them against the installed Fabric API.
     * v10: Forgified Fabric API dependencies now normalize to "nrof" — the
     * framework lives on the NeoForge side, carried by NROF itself, with its
     * interfaces backed by the real Fabric API on the classpath. Converted
     * metadata must be regenerated again.
     * v11: Forgified Fabric API dependency ids are the real Fabric API ids in
     * underscore form (fabric_api umbrella + fabric_* modules), normalized to
     * "nrof" in converted metadata.
     * v12: TableRemapper resolves unknown Minecraft owners' hierarchy from the
     * runtime class bytes (resource stream, never class definition) — fixes
     * inherited interface members on MC enums/interfaces like
     * DyeColor.getSerializedName (StringRepresentable).
     */
    public static final String CACHE_FORMAT = "nrof-cache-v12";

    private NrofVersion() {
    }
}
