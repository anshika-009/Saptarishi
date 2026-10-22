package com.saptarishi.backend.file;

import static org.junit.jupiter.api.Assertions.*;

import com.saptarishi.backend.file.FileService.FileView;
import com.saptarishi.backend.graph.GraphBuilder;
import com.saptarishi.backend.graph.GraphRepository;
import com.saptarishi.backend.parse.ParsedFile;
import com.saptarishi.backend.parse.ParsedFile.RawImport;
import com.saptarishi.backend.parse.ParsedFile.Symbol;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@SpringBootTest
@Testcontainers
class FileServiceTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16-alpine");

    private static final String SHA = "c".repeat(40);
    private static final String SERVICE = "src/app/Service.java";
    private static final String REPO = "src/app/Repo.java";

    @Autowired GraphRepository repository;
    @Autowired JdbcTemplate jdbc;
    @TempDir Path tmp;

    private FileService service;
    private Path dir;

    @BeforeEach
    void seed() throws IOException {
        if (!repository.exists(SHA)) {
            List<ParsedFile> parsed = List.of(
                    new ParsedFile(SERVICE, "java", "app", List.of(new RawImport("app.Repo", false, false)),
                            List.of(new Symbol("Service", "CLASS", 3)), 40, 3, 12, true),
                    new ParsedFile(REPO, "java", "app", List.of(),
                            List.of(new Symbol("Repo", "INTERFACE", 3)), 10, 0, 4, true));
            repository.save(SHA, "o", "r", new GraphBuilder().build(parsed, false), parsed);
        }
        dir = tmp.resolve("repos").resolve(SHA).resolve("src/src/app");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("Service.java"), "class Service {}");
        Files.writeString(dir.resolve("Repo.java"), "interface Repo {}");
        service = new FileService(jdbc, tmp.toString());
    }

    @Test
    void returnsContentMetricsAndBothEdgeDirections() throws IOException {
        FileView svc = service.read(SHA, SERVICE).orElseThrow();
        assertEquals("class Service {}", svc.content());
        assertEquals("java", svc.language());
        assertEquals(40, svc.tokens());
        assertEquals(List.of(REPO), svc.imports());
        assertTrue(svc.importedBy().isEmpty());

        FileView repo = service.read(SHA, REPO).orElseThrow();
        assertEquals(List.of(SERVICE), repo.importedBy());
    }

    @Test
    void unknownInputsAreEmpty() throws IOException {
        assertTrue(service.read(SHA, "../../../etc/passwd").isEmpty());
        assertTrue(service.read(SHA, "src/app/Nope.java").isEmpty());
        assertTrue(service.read("not-a-sha", SERVICE).isEmpty());
        assertTrue(service.read("d".repeat(40), SERVICE).isEmpty());
    }

    @Test
    void missingCachedSourceThrows() throws IOException {
        Files.delete(dir.resolve("Repo.java"));
        assertThrows(NoSuchFileException.class, () -> service.read(SHA, REPO));
    }

    @Test
    void largeFilesAreTruncated() throws IOException {
        Files.writeString(dir.resolve("Service.java"), "x".repeat(FileService.MAX_BYTES + 10));
        FileView view = service.read(SHA, SERVICE).orElseThrow();
        assertTrue(view.truncated());
        assertEquals(FileService.MAX_BYTES, view.content().length());
    }
}