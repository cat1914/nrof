package com.cat.nrof.model;

import java.util.ArrayList;
import java.util.List;

/**
 * Ported subset of NeoForge's mod metadata model (the data normally exposed by
 * FML's {@code IModInfo}). Produced by {@code ModsTomlParser} from
 * {@code META-INF/neoforge.mods.toml}.
 */
public record NeoForgeModInfo(
        String modId,
        String version,
        String displayName,
        String description,
        List<String> authors,
        String credits,
        String license,
        String logoFile,
        String issueTrackerURL,
        String displayURL,
        String environment,
        List<NeoForgeDependency> dependencies,
        List<String> mixinConfigs) {

    public NeoForgeModInfo {
        authors = authors == null ? List.of() : List.copyOf(authors);
        dependencies = dependencies == null ? List.of() : List.copyOf(dependencies);
        mixinConfigs = mixinConfigs == null ? List.of() : List.copyOf(mixinConfigs);
        if (modId == null || modId.isBlank()) {
            throw new IllegalArgumentException("NeoForge mod entry without a modId");
        }
        version = normalizeVersion(version);
    }

    /**
     * NeoForge tolerates unresolved placeholders such as {@code ${file.jarVersion}};
     * Fabric loader metadata needs a concrete string, so placeholders collapse to a
     * fallback while the raw value stays available through the custom block.
     */
    private static String normalizeVersion(String raw) {
        if (raw == null || raw.isBlank() || raw.contains("${")) {
            return "0.0.0";
        }
        return raw.trim();
    }

    public static Builder builder(String modId) {
        return new Builder(modId);
    }

    public static final class Builder {
        private final String modId;
        private String version = "0.0.0";
        private String displayName;
        private String description = "";
        private final List<String> authors = new ArrayList<>();
        private String credits;
        private String license;
        private String logoFile;
        private String issueTrackerURL;
        private String displayURL;
        private String environment = "*";
        private final List<NeoForgeDependency> dependencies = new ArrayList<>();
        private final List<String> mixinConfigs = new ArrayList<>();

        private Builder(String modId) {
            this.modId = modId;
        }

        public Builder version(String v) {
            if (v != null && !v.isBlank()) {
                this.version = v;
            }
            return this;
        }

        public Builder displayName(String v) {
            this.displayName = v;
            return this;
        }

        public Builder description(String v) {
            this.description = v == null ? "" : v;
            return this;
        }

        public Builder authors(List<String> v) {
            if (v != null) {
                this.authors.addAll(v);
            }
            return this;
        }

        public Builder credits(String v) {
            this.credits = v;
            return this;
        }

        public Builder license(String v) {
            this.license = v;
            return this;
        }

        public Builder logoFile(String v) {
            this.logoFile = v;
            return this;
        }

        public Builder issueTrackerURL(String v) {
            this.issueTrackerURL = v;
            return this;
        }

        public Builder displayURL(String v) {
            this.displayURL = v;
            return this;
        }

        public Builder environment(String v) {
            if (v != null && !v.isBlank()) {
                this.environment = v;
            }
            return this;
        }

        public Builder dependencies(List<NeoForgeDependency> v) {
            if (v != null) {
                this.dependencies.addAll(v);
            }
            return this;
        }

        public Builder mixinConfigs(List<String> v) {
            if (v != null) {
                this.mixinConfigs.addAll(v);
            }
            return this;
        }

        public NeoForgeModInfo build() {
            return new NeoForgeModInfo(modId, version,
                    displayName == null || displayName.isBlank() ? modId : displayName,
                    description, authors, credits, license, logoFile,
                    issueTrackerURL, displayURL, environment, dependencies, mixinConfigs);
        }
    }
}
