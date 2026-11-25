package com.jacob0225.conduit.restart;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class RestartSpecTest {

    @Test
    void roundTripsUnicodeAndSpaces() throws IOException {
        RestartSpec spec = new RestartSpec(1234, "C:\\Users\\me\\Fabric 26.3 (3)",
                List.of("java", "--username", "Spieler-Ü", "--gameDir", "C:\\a b\\c"));
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        spec.write(out);
        assertEquals(spec, RestartSpec.read(new ByteArrayInputStream(out.toByteArray())));
    }

    @Test
    void rejectsForeignInput() {
        byte[] junk = "hello, not a spec".getBytes();
        assertThrows(IOException.class, () -> RestartSpec.read(new ByteArrayInputStream(junk)));
    }

    @Test
    void rejectsTruncatedInput() throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        new RestartSpec(1, "dir", List.of("java", "x")).write(out);
        byte[] cut = Arrays.copyOf(out.toByteArray(), out.size() - 3);
        assertThrows(IOException.class, () -> RestartSpec.read(new ByteArrayInputStream(cut)));
    }

    @Test
    void rejectsEmptyCommandAndBadPid() {
        assertThrows(IllegalArgumentException.class, () -> new RestartSpec(1, "dir", List.of()));
        assertThrows(IllegalArgumentException.class, () -> new RestartSpec(0, "dir", List.of("java")));
    }
}
