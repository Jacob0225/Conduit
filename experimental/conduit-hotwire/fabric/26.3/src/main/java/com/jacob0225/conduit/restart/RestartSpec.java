package com.jacob0225.conduit.restart;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * What the restart helper needs: which process to wait for, where to start the
 * new game, and the exact command. Sent to the helper over its stdin, so the
 * command (which contains the launcher's session arguments) is never written
 * to disk or exposed in the helper's own command line.
 *
 * <p>Pure Java on purpose: the helper runs in a bare JVM with only Conduit's
 * jar on the classpath.
 */
public record RestartSpec(long parentPid, String workDir, List<String> command) {

    private static final int MAGIC = 0xC0D017E5;
    private static final int VERSION = 1;
    private static final int MAX_TOKENS = 4096;
    private static final int MAX_STRING_BYTES = 1 << 20;

    public RestartSpec {
        if (parentPid <= 0) throw new IllegalArgumentException("parentPid must be positive");
        if (workDir == null || workDir.isBlank()) throw new IllegalArgumentException("workDir is required");
        if (command == null || command.isEmpty()) throw new IllegalArgumentException("command is required");
        if (command.size() > MAX_TOKENS) throw new IllegalArgumentException("command has too many tokens");
        command = List.copyOf(command);
    }

    public void write(OutputStream out) throws IOException {
        DataOutputStream data = new DataOutputStream(out);
        data.writeInt(MAGIC);
        data.writeInt(VERSION);
        data.writeLong(parentPid);
        writeString(data, workDir);
        data.writeInt(command.size());
        for (String token : command) writeString(data, token);
        data.flush();
    }

    public static RestartSpec read(InputStream in) throws IOException {
        DataInputStream data = new DataInputStream(in);
        if (data.readInt() != MAGIC) throw new IOException("Not a Conduit restart spec");
        if (data.readInt() != VERSION) throw new IOException("Unsupported restart spec version");
        long pid = data.readLong();
        String workDir = readString(data);
        int count = data.readInt();
        if (count <= 0 || count > MAX_TOKENS) throw new IOException("Bad token count: " + count);
        List<String> command = new ArrayList<>(count);
        for (int i = 0; i < count; i++) command.add(readString(data));
        try {
            return new RestartSpec(pid, workDir, command);
        } catch (IllegalArgumentException e) {
            throw new IOException(e.getMessage(), e);
        }
    }

    private static void writeString(DataOutputStream data, String value) throws IOException {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_STRING_BYTES) throw new IOException("Argument too long");
        data.writeInt(bytes.length);
        data.write(bytes);
    }

    private static String readString(DataInputStream data) throws IOException {
        int length = data.readInt();
        if (length < 0 || length > MAX_STRING_BYTES) throw new IOException("Bad string length: " + length);
        byte[] bytes = data.readNBytes(length);
        if (bytes.length != length) throw new IOException("Truncated restart spec");
        return new String(bytes, StandardCharsets.UTF_8);
    }
}
