package com.cat.nrof.discovery;

import com.cat.nrof.model.NeoForgeDependency;
import com.cat.nrof.model.NeoForgeModInfo;
import com.electronwill.nightconfig.core.Config;
import com.electronwill.nightconfig.core.CommentedConfig;
import com.electronwill.nightconfig.toml.TomlFormat;
import com.electronwill.nightconfig.toml.TomlParser;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Parses {@code META-INF/neoforge.mods.toml} using night-config — the same TOML
 * library family FML itself uses — into the ported {@link NeoForgeModInfo} model.
 */
public final class ModsTomlParser {

    public static List<NeoForgeModInfo> parse(InputStream in) throws IOException {
        TomlParser parser = TomlFormat.instance().createParser();
        CommentedConfig toml;
        try (Reader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
            toml = parser.parse(reader);
        }

        List<NeoForgeModInfo> result = new ArrayList<>();
        for (Config mod : configList(toml.get("mods"))) {
            result.add(readMod(mod, toml));
        }
        if (result.isEmpty()) {
            throw new IOException("neoforge.mods.toml contains no [[mods]] entries");
        }
        return result;
    }

    private static NeoForgeModInfo readMod(Config mod, Config root) {
        String modId = mod.get("modId");
        NeoForgeModInfo.Builder b = NeoForgeModInfo.builder(modId)
                .version(mod.get("version"))
                .displayName(mod.get("displayName"))
                .description(mod.get("description"))
                .credits(mod.get("credits"))
                .logoFile(mod.get("logoFile"))
                .issueTrackerURL(root.get("issueTrackerURL"))
                .displayURL(firstNonNull(mod.get("displayURL"), root.get("displayURL")))
                .environment(str(mod, "environment"));

        b.authors(readAuthors(mod));
        b.license(firstNonNull(mod.get("license"), root.get("license")));
        b.mixinConfigs(readMixinConfigs(root));
        b.dependencies(readDependencies(mod));
        return b.build();
    }

    private static String str(Config cfg, String key) {
        String v = cfg.get(key);
        return v == null || v.isBlank() ? "*" : v.trim().toLowerCase(Locale.ROOT);
    }

    private static String firstNonNull(Object... values) {
        for (Object v : values) {
            if (v instanceof String s && !s.isBlank()) {
                return s;
            }
        }
        return null;
    }

    private static List<String> readAuthors(Config mod) {
        Object raw = mod.get("authors");
        if (raw == null) {
            return List.of();
        }
        if (raw instanceof List<?> list) {
            List<String> out = new ArrayList<>();
            for (Object o : list) {
                out.addAll(splitAuthors(String.valueOf(o)));
            }
            return out;
        }
        return splitAuthors(String.valueOf(raw));
    }

    private static List<String> splitAuthors(String s) {
        if (s.isBlank()) {
            return List.of();
        }
        return Arrays.stream(s.split(","))
                .map(String::trim)
                .filter(a -> !a.isEmpty())
                .toList();
    }

    /**
     * {@code [[mixins]] config = "example.mixins.json"} — NeoForge declares mixin
     * configs as table entries; these are the "mixin events" the ported bus gets
     * notified about before being forwarded to Fabric.
     */
    private static List<String> readMixinConfigs(Config root) {
        List<String> out = new ArrayList<>();
        for (Config entry : configList(root.get("mixins"))) {
            String cfg = entry.get("config");
            if (cfg != null && !cfg.isBlank()) {
                out.add(cfg);
            }
        }
        return out;
    }

    /**
     * Dependencies live at the ROOT level as {@code [[dependencies."modid"]]}
     * table arrays (the quoted table name is just grouping — the real target is
     * the {@code modId} key inside each entry).
     */
    private static List<NeoForgeDependency> readDependencies(Config root) {
        List<NeoForgeDependency> out = new ArrayList<>();
        Object depsRaw = root.get("dependencies");
        if (!(depsRaw instanceof Config deps)) {
            return out;
        }
        for (Map.Entry<String, Object> e : deps.valueMap().entrySet()) {
            for (Config c : configList(e.getValue())) {
                String modId = c.get("modId") != null ? c.get("modId") : e.getKey();
                out.add(new NeoForgeDependency(
                        modId,
                        str(c, "type"),
                        c.get("versionRange"),
                        str(c, "ordering"),
                        str(c, "side")));
            }
        }
        return out;
    }

    /** TOML array-of-tables arrives as a List of Config entries. */
    private static List<Config> configList(Object raw) {
        if (!(raw instanceof List<?> list)) {
            return List.of();
        }
        List<Config> out = new ArrayList<>();
        for (Object o : list) {
            if (o instanceof Config c) {
                out.add(c);
            }
        }
        return out;
    }

    private ModsTomlParser() {
    }
}
