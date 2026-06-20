package com.jacob0225.conduit.security;

import java.util.regex.Pattern;

/**
 * Central home for every security-critical input check.
 *
 * All methods throw {@link SecurityException} on failure so callers can
 * treat validation errors uniformly without forgetting to check a return value.
 */
public final class InputValidator {

    // ── Patterns ─────────────────────────────────────────────────────────────

    /** Fabric mod IDs: lowercase letters, digits, underscores, hyphens, 1-64 chars. */
    private static final Pattern MOD_ID =
            Pattern.compile("^[a-z0-9_\\-]{1,64}$");

    /** Modrinth project IDs are 8 alphanumeric characters. */
    private static final Pattern MODRINTH_ID =
            Pattern.compile("^[A-Za-z0-9]{8}$");

    /** CurseForge project IDs are purely numeric (up to 10 digits). */
    private static final Pattern CURSEFORGE_ID =
            Pattern.compile("^[0-9]{1,10}$");

    /** SHA-256 hash: exactly 64 lowercase hex chars. */
    private static final Pattern SHA256 =
            Pattern.compile("^[0-9a-f]{64}$");

    /** SHA-512 hash: exactly 128 lowercase hex chars. */
    private static final Pattern SHA512 =
            Pattern.compile("^[0-9a-f]{128}$");

    /** Semver-ish version string, e.g. "0.6.0" or "1.21.4+fabric". */
    private static final Pattern VERSION =
            Pattern.compile("^[A-Za-z0-9.+\\-_]{1,64}$");

    /** Fragments that must never appear in any string from the server. */
    private static final String[] FORBIDDEN_SUBSTRINGS =
            { "http://", "https://", "://", "../", "..\\", "/", "\\" };

    /** Maximum allowed length for generic display strings (names, server name). */
    private static final int MAX_DISPLAY_LEN = 256;

    private InputValidator() {}

    // ── Public API ────────────────────────────────────────────────────────────

    /** @throws SecurityException if {@code value} is null, blank, or contains URL/path chars. */
    public static String requireSafeString(String field, String value) {
        if (value == null || value.isBlank())
            throw new SecurityException("Field '" + field + "' must not be null or blank");
        if (value.length() > MAX_DISPLAY_LEN)
            throw new SecurityException("Field '" + field + "' exceeds max length");
        for (String forbidden : FORBIDDEN_SUBSTRINGS) {
            if (value.contains(forbidden))
                throw new SecurityException(
                        "Field '" + field + "' contains forbidden substring: " + forbidden);
        }
        return value;
    }

    public static String requireModId(String value) {
        requireSafeString("modId", value);
        if (!MOD_ID.matcher(value).matches())
            throw new SecurityException("Invalid modId: " + value);
        return value;
    }

    public static String requireModrinthId(String value) {
        if (value == null || value.isEmpty()) return ""; // optional
        if (!MODRINTH_ID.matcher(value).matches())
            throw new SecurityException("Invalid Modrinth project ID: " + value);
        return value;
    }

    public static String requireCurseForgeId(String value) {
        if (value == null || value.isEmpty()) return ""; // optional
        if (!CURSEFORGE_ID.matcher(value).matches())
            throw new SecurityException("Invalid CurseForge project ID: " + value);
        return value;
    }

    public static String requireHash(String algorithm, String value) {
        if (value == null)
            throw new SecurityException("Hash must not be null");
        String lower = value.toLowerCase();
        boolean valid = switch (algorithm) {
            case "SHA-256" -> SHA256.matcher(lower).matches();
            case "SHA-512" -> SHA512.matcher(lower).matches();
            default -> throw new SecurityException("Unknown hash algorithm: " + algorithm);
        };
        if (!valid)
            throw new SecurityException("Hash does not match expected format for " + algorithm);
        return lower;
    }

    public static String requireVersion(String value) {
        requireSafeString("version", value);
        if (!VERSION.matcher(value).matches())
            throw new SecurityException("Invalid version string: " + value);
        return value;
    }

    public static String requireHashAlgorithm(String value) {
        return switch (value) {
            case "SHA-256", "SHA-512" -> value;
            default -> throw new SecurityException("Unsupported hash algorithm: " + value);
        };
    }
}
