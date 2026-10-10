package com.cat.nrof.convert;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The converted metadata in Fabric's structure. Serialized it is a drop-in
 * {@code fabric.mod.json} document ("Binary structure" on the Fabric side) that
 * the Fabric mod index can parse natively once injected.
 */
public final class NrofModMetadata {
    public final String id;
    public final String version;
    public final String name;
    public final String description;
    public final List<String> authors = new ArrayList<>();
    public String homepage;
    public String issues;
    public String license;
    public String icon;
    public final List<String> mixins = new ArrayList<>();
    public final Map<String, String> depends = new LinkedHashMap<>();
    public final Map<String, String> suggests = new LinkedHashMap<>();
    /** Raw NeoForge metadata + provenance, preserved under custom.nrof. */
    public final JsonObject nrofCustom = new JsonObject();

    private final transient String sourceModId;

    public NrofModMetadata(String sourceModId, String id, String version, String name, String description) {
        this.sourceModId = sourceModId;
        this.id = id;
        this.version = version;
        this.name = name;
        this.description = description;
    }

    /** The NeoForge mod id this metadata was converted from. */
    public String sourceModId() {
        return sourceModId;
    }

    /** Serializes to a fabric.mod.json-shaped JSON object. */
    public JsonObject toJson() {
        JsonObject json = new JsonObject();
        json.addProperty("schemaVersion", 1);
        json.addProperty("id", id);
        json.addProperty("version", version);
        json.addProperty("name", name);
        if (description != null && !description.isBlank()) {
            json.addProperty("description", description);
        }
        if (!authors.isEmpty()) {
            JsonArray arr = new JsonArray();
            authors.forEach(arr::add);
            json.add("authors", arr);
        }
        JsonObject contact = new JsonObject();
        if (homepage != null) {
            contact.addProperty("homepage", homepage);
        }
        if (issues != null) {
            contact.addProperty("issues", issues);
        }
        if (contact.size() > 0) {
            json.add("contact", contact);
        }
        if (license != null) {
            json.addProperty("license", license);
        }
        if (icon != null) {
            json.addProperty("icon", icon);
        }
        json.addProperty("environment", "*");
        if (!mixins.isEmpty()) {
            JsonArray arr = new JsonArray();
            mixins.forEach(arr::add);
            json.add("mixins", arr);
        }
        if (!depends.isEmpty()) {
            JsonObject o = new JsonObject();
            depends.forEach(o::addProperty);
            json.add("depends", o);
        }
        if (!suggests.isEmpty()) {
            JsonObject o = new JsonObject();
            suggests.forEach(o::addProperty);
            json.add("suggests", o);
        }
        JsonObject custom = new JsonObject();
        custom.add("nrof", nrofCustom);
        json.add("custom", custom);
        return json;
    }
}
