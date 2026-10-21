package com.saptarishi.backend.ingest;

import static org.junit.jupiter.api.Assertions.*;

import com.saptarishi.backend.ingest.SafeUnzipper.Limits;
import com.saptarishi.backend.ingest.SafeUnzipper.UnsafeArchiveException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SafeUnzipperTest {

    @TempDir Path tmp;

    private Path makeZip(Map<String, byte[]> entries) throws IOException {
        Path z = tmp.resolve("t.zip");
        try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(z))) {
            for (var e : entries.entrySet()) {
                out.putNextEntry(new ZipEntry(e.getKey()));
                out.write(e.getValue());
                out.closeEntry();
            }
        }
        return z;
    }

    private static byte[] bytes(String s) { return s.getBytes(StandardCharsets.UTF_8); }

    @Test void keepsSourceAndSkipsJunk() throws IOException {
        Map<String, byte[]> m = new LinkedHashMap<>();
        m.put("repo-abc/src/Main.java", bytes("class Main {}"));
        m.put("repo-abc/README.md", bytes("# hi"));
        m.put("repo-abc/node_modules/x/index.js", bytes("x"));
        m.put("repo-abc/logo.png", new byte[] {1, 2, 3});
        Path out = tmp.resolve("out");

        int n = new SafeUnzipper(Limits.defaults(), new SourceFilter()).extract(makeZip(m), out, true);

        assertEquals(2, n);
        assertTrue(Files.exists(out.resolve("src/Main.java")));
        assertTrue(Files.exists(out.resolve("README.md")));
        assertFalse(Files.exists(out.resolve("node_modules")));
        assertFalse(Files.exists(out.resolve("logo.png")));
    }

    @Test void rejectsZipSlip() throws IOException {
        Map<String, byte[]> m = new LinkedHashMap<>();
        m.put("repo-abc/../../evil.txt", bytes("pwned"));
        var u = new SafeUnzipper(Limits.defaults(), new SourceFilter());
        assertThrows(UnsafeArchiveException.class, () -> u.extract(makeZip(m), tmp.resolve("out"), true));
        assertFalse(Files.exists(tmp.resolve("evil.txt")));
    }

    @Test void rejectsZipBomb() throws IOException {
        Map<String, byte[]> m = new LinkedHashMap<>();
        m.put("repo/data.txt", new byte[5000]);
        var u = new SafeUnzipper(new Limits(100, 1000, 1000, 500), new SourceFilter());
        assertThrows(UnsafeArchiveException.class, () -> u.extract(makeZip(m), tmp.resolve("out"), true));
    }

    @Test void dropsOversizedFileWithoutFailing() throws IOException {
        Map<String, byte[]> m = new LinkedHashMap<>();
        m.put("repo/Big.java", new byte[600]);
        m.put("repo/Small.java", bytes("class S {}"));
        var u = new SafeUnzipper(new Limits(100, 10_000, 1000, 500), new SourceFilter());
        int n = u.extract(makeZip(m), tmp.resolve("out"), true);
        assertEquals(1, n);
        assertFalse(Files.exists(tmp.resolve("out/Big.java")));
        assertTrue(Files.exists(tmp.resolve("out/Small.java")));
    }
}