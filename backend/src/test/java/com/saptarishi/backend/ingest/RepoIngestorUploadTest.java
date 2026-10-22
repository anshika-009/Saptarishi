package com.saptarishi.backend.ingest;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.*;

import com.saptarishi.backend.ingest.RepoIngestor.IngestResult;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RepoIngestorUploadTest {

    @TempDir Path tmp;
    private RepoIngestor ingestor;

    @BeforeEach
    void setUp() {
        ingestor = new RepoIngestor(new GithubClient(""), new SourceFilter(), tmp.resolve("data").toString());
    }

    private Path zip(String... names) throws IOException {
        Path z = Files.createTempFile(tmp, "up-", ".zip");
        try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(z))) {
            for (String name : names) {
                out.putNextEntry(new ZipEntry(name));
                out.write("class A {}".getBytes(UTF_8));
                out.closeEntry();
            }
        }
        return z;
    }

    private static List<String> paths(IngestResult r) {
        return r.files().stream().map(IndexedFile::path).toList();
    }

    @Test
    void stripsASingleTopLevelFolder() throws IOException {
        IngestResult r = ingestor.ingestZip("proj", "1".repeat(40), zip("proj/src/A.java", "proj/README.md"));
        assertEquals(List.of("README.md", "src/A.java"), paths(r));
        assertEquals("upload", r.owner());
        assertEquals("proj", r.repo());
    }

    @Test
    void keepsLayoutWhenThereIsNoSingleRoot() throws IOException {
        IngestResult r = ingestor.ingestZip("flat", "2".repeat(40), zip("A.java", "lib/B.java"));
        assertEquals(List.of("A.java", "lib/B.java"), paths(r));
    }

    @Test
    void ignoresMacosxResourceForks() throws IOException {
        IngestResult r = ingestor.ingestZip("mac", "3".repeat(40), zip("proj/A.java", "__MACOSX/proj/._A.java"));
        assertEquals(List.of("A.java"), paths(r));
    }

    @Test
    void rejectsZipSlipEntries() throws IOException {
        Path z = zip("../evil.java", "ok.java");
        assertThrows(SafeUnzipper.UnsafeArchiveException.class, () -> ingestor.ingestZip("bad", "4".repeat(40), z));
        assertFalse(Files.exists(tmp.resolve("evil.java")));
    }

    @Test
    void rejectsCorruptArchives() throws IOException {
        Path bad = Files.createTempFile(tmp, "bad-", ".zip");
        Files.writeString(bad, "this is not a zip file");
        assertThrows(IllegalArgumentException.class, () -> ingestor.ingestZip("bad", "5".repeat(40), bad));
    }

    @Test
    void sameShaIsServedFromCache() throws IOException {
        String sha = "6".repeat(40);
        assertFalse(ingestor.ingestZip("proj", sha, zip("proj/A.java")).cached());
        assertTrue(ingestor.ingestZip("proj", sha, zip("proj/A.java")).cached());
    }
}