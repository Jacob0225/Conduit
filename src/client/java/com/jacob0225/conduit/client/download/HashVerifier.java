package com.jacob0225.conduit.client.download;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * Cryptographic hash verification for downloaded mod JARs.
 *
 * Always verify BEFORE moving a file out of the staging directory.
 * On failure, callers must delete the staging file and abort installation.
 */
public final class HashVerifier {

    private static final int BUFFER_SIZE = 8192;

    private HashVerifier() {}

    /**
     * Computes the hash of {@code file} using {@code algorithm} and compares it
     * (case-insensitively) against {@code expectedHex}.
     *
     * @throws VerificationException if hashes do not match
     * @throws IOException           if the file cannot be read
     */
    public static void verify(Path file, String algorithm, String expectedHex)
            throws IOException, VerificationException {

        String actual = computeHex(file, algorithm);
        if (!actual.equalsIgnoreCase(expectedHex)) {
            throw new VerificationException(
                    "Hash mismatch for " + file.getFileName() + "\n" +
                    "  expected: " + expectedHex + "\n" +
                    "  actual:   " + actual
            );
        }
    }

    /**
     * Verifies the file against BOTH the API-reported hash AND the server-reported hash.
     * Both must match. If either fails the file is rejected.
     */
    public static void verifyBoth(
            Path file,
            String algorithm,
            String apiHash,
            String serverHash
    ) throws IOException, VerificationException {
        String actual = computeHex(file, algorithm);

        if (!actual.equalsIgnoreCase(apiHash))
            throw new VerificationException(
                    "API hash mismatch for " + file.getFileName());

        if (!actual.equalsIgnoreCase(serverHash))
            throw new VerificationException(
                    "Server hash mismatch for " + file.getFileName() +
                    " — possible tampering detected");
    }

    // ── Private ───────────────────────────────────────────────────────────────

    static String computeHex(Path file, String algorithm) throws IOException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance(algorithm);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalArgumentException("Unknown algorithm: " + algorithm, e);
        }

        try (InputStream in = Files.newInputStream(file)) {
            byte[] buf = new byte[BUFFER_SIZE];
            int read;
            while ((read = in.read(buf)) != -1) {
                digest.update(buf, 0, read);
            }
        }

        byte[] bytes = digest.digest();
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) sb.append(String.format("%02x", b));
        return sb.toString();
    }

    /** Thrown when a downloaded file's hash does not match the expected value. */
    public static class VerificationException extends Exception {
        public VerificationException(String message) { super(message); }
    }
}
