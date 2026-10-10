package com.cat.nrof.runtime;

import com.cat.nrof.convert.NrofModMetadata;
import net.neoforged.neoforgespi.language.IConfigurable;
import net.neoforged.neoforgespi.language.IModFileInfo;
import net.neoforged.neoforgespi.language.IModInfo;
import net.neoforged.neoforgespi.language.IModLanguageLoader;
import org.apache.maven.artifact.versioning.ArtifactVersion;
import org.apache.maven.artifact.versioning.DefaultArtifactVersion;

import java.net.URL;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Adapter from NROF's converted {@link NrofModMetadata} to the real FML SPI
 * {@link IModInfo} interface (as shaded in the JiJ'd fancymodloader jar), so the
 * real {@code net.neoforged.fml.ModContainer} can be constructed for converted mods.
 */
public final class NrofModInfo implements IModInfo {

    private final NrofModMetadata metadata;
    private final ArtifactVersion version;

    public NrofModInfo(NrofModMetadata metadata) {
        this.metadata = metadata;
        this.version = new DefaultArtifactVersion(metadata.version);
    }

    @Override
    public IModFileInfo getOwningFile() {
        return null;
    }

    @Override
    public String getModId() {
        return metadata.id;
    }

    @Override
    public String getDisplayName() {
        return metadata.name == null || metadata.name.isBlank() ? metadata.id : metadata.name;
    }

    @Override
    public String getDescription() {
        return metadata.description == null ? "" : metadata.description;
    }

    @Override
    public ArtifactVersion getVersion() {
        return version;
    }

    @Override
    public List<? extends ModVersion> getDependencies() {
        return List.of();
    }

    @Override
    public List<? extends net.neoforged.neoforgespi.locating.ForgeFeature.Bound> getForgeFeatures() {
        return List.of();
    }

    @Override
    public String getNamespace() {
        return metadata.id;
    }

    @Override
    public Map<String, Object> getModProperties() {
        return Map.of();
    }

    @Override
    public Optional<URL> getUpdateURL() {
        return Optional.empty();
    }

    @Override
    public Optional<URL> getModURL() {
        if (metadata.homepage == null || metadata.homepage.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(new URL(metadata.homepage));
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    @Override
    public Optional<String> getLogoFile() {
        return Optional.ofNullable(metadata.icon);
    }

    @Override
    public boolean getLogoBlur() {
        return false;
    }

    @Override
    public IModLanguageLoader getLoader() {
        // NROF drives the javafml lifecycle itself; no FML language loader instance.
        return null;
    }

    @Override
    public IConfigurable getConfig() {
        return new IConfigurable() {
            @Override
            public <T> Optional<T> getConfigElement(String... key) {
                return Optional.empty();
            }

            @Override
            public List<? extends IConfigurable> getConfigList(String... key) {
                return List.of();
            }
        };
    }
}
