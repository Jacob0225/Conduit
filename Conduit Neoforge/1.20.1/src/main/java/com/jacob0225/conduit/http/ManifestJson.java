package com.jacob0225.conduit.http;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.jacob0225.conduit.manifest.ModEntry;
import com.jacob0225.conduit.network.ManifestPayload;

/**
 * JSON (de)serialization for {@link ManifestPayload}, shared by the server's
 * HTTP endpoint (write) and the client's HTTP fetch (read).
 *
 * <p>This is deliberately <b>not</b> Gson-reflection-based. Every field is read
 * and validated explicitly via {@link ModEntry}'s constructor, which runs the
 * full {@code InputValidator} gate. So a tampered or malformed HTTP response
 * is rejected with the same rigor as a bad network packet would have been —
 * the manifest is attacker-controlled data arriving over plain HTTP.
 *
 * <p>Schema (JSON):
 * <pre>{@code
 * {
 *   "schemaVersion": 1,
 *   "manifestVersion": 3,
 *   "serverName": "My Server",
 *   "mods": [
 *     {
 *       "modId": "sodium",
 *       "displayName": "Sodium",
 *       "requiredVersion": "0.6.13",
 *       "required": true,
 *       "modrinthProjectId": "AANobbMI",
 *       "curseforgeProjectId": "",
 *       "hashAlgorithm": "SHA-512",
 *       "expectedHash": "<lowercase hex>"
 *     }
 *   ]
 * }
 * }</pre>
 */
public final class ManifestJson {

    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

    private ManifestJson() {}

    /** Serialize a validated payload to JSON for the HTTP response. */
    public static String toJson(ManifestPayload payload) {
        JsonObject root = new JsonObject();
        root.addProperty("schemaVersion", payload.schemaVersion());
        root.addProperty("manifestVersion", payload.manifestVersion());
        root.addProperty("serverName", payload.serverName());

        JsonArray mods = new JsonArray();
        for (ModEntry m : payload.mods()) {
            JsonObject o = new JsonObject();
            o.addProperty("modId", m.modId());
            o.addProperty("displayName", m.displayName());
            o.addProperty("requiredVersion", m.requiredVersion());
            o.addProperty("required", m.required());
            o.addProperty("modrinthProjectId", m.modrinthProjectId());
            o.addProperty("curseforgeProjectId", m.curseforgeProjectId());
            o.addProperty("hashAlgorithm", m.hashAlgorithm());
            o.addProperty("expectedHash", m.expectedHash());
            mods.add(o);
        }
        root.add("mods", mods);
        return GSON.toJson(root);
    }

    /**
     * Parse and validate a JSON manifest. Throws on any structural or content
     * problem (missing field, bad hash, oversized mod count, etc.) so callers
     * can treat "unparseable" and "invalid" identically.
     *
     * @throws IllegalArgumentException if the JSON is malformed or fails validation
     */
    public static ManifestPayload fromJson(String json) {
        JsonElement root = JsonParser.parseString(json);
        if (root == null || !root.isJsonObject())
            throw new IllegalArgumentException("Manifest root is not a JSON object");

        JsonObject obj = root.getAsJsonObject();
        int schemaVersion = getAsInt(obj, "schemaVersion");
        int manifestVersion = getAsInt(obj, "manifestVersion");
        String serverName = getAsString(obj, "serverName");

        JsonArray modsArr = obj.has("mods") && obj.get("mods").isJsonArray()
                ? obj.getAsJsonArray("mods")
                : new JsonArray();

        if (modsArr.size() > ManifestPayload.MAX_MODS)
            throw new IllegalArgumentException(
                    "Manifest mod count exceeds max: " + modsArr.size());

        java.util.List<ModEntry> mods = new java.util.ArrayList<>(modsArr.size());
        for (JsonElement e : modsArr) {
            if (!e.isJsonObject())
                throw new IllegalArgumentException("Mod entry is not a JSON object");
            JsonObject m = e.getAsJsonObject();
            mods.add(new ModEntry(
                    getAsString(m, "modId"),
                    getAsString(m, "displayName"),
                    getAsString(m, "requiredVersion"),
                    getAsBool(m, "required"),
                    getOptionalString(m, "modrinthProjectId"),
                    getOptionalString(m, "curseforgeProjectId"),
                    getAsString(m, "hashAlgorithm"),
                    getAsString(m, "expectedHash")
            ));
        }

        return new ManifestPayload(schemaVersion, manifestVersion, serverName, mods);
    }

    // ── Strict helpers — every missing/wrong-typed field is a hard error ──────

    private static int getAsInt(JsonObject o, String key) {
        if (!o.has(key) || !o.get(key).isJsonPrimitive())
            throw new IllegalArgumentException("Missing or non-int field: " + key);
        return o.get(key).getAsInt();
    }

    private static boolean getAsBool(JsonObject o, String key) {
        if (!o.has(key) || !o.get(key).isJsonPrimitive())
            throw new IllegalArgumentException("Missing or non-boolean field: " + key);
        return o.get(key).getAsBoolean();
    }

    private static String getAsString(JsonObject o, String key) {
        if (!o.has(key) || !o.get(key).isJsonPrimitive())
            throw new IllegalArgumentException("Missing or non-string field: " + key);
        return o.get(key).getAsString();
    }

    /** Required-by-spec fields that may legitimately be absent (empty). */
    private static String getOptionalString(JsonObject o, String key) {
        if (!o.has(key) || o.get(key).isJsonNull()) return "";
        return o.get(key).getAsString();
    }
}
