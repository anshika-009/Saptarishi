package com.saptarishi.backend.search;

import static org.junit.jupiter.api.Assertions.*;

import com.saptarishi.backend.graph.GraphBuilder;
import com.saptarishi.backend.graph.GraphRepository;
import com.saptarishi.backend.parse.ParsedFile;
import com.saptarishi.backend.parse.ParsedFile.RawImport;
import com.saptarishi.backend.parse.ParsedFile.Symbol;
import com.saptarishi.backend.search.SearchService.FileHit;
import com.saptarishi.backend.search.SearchService.Results;
import com.saptarishi.backend.search.SearchService.SymbolHit;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@SpringBootTest
@Testcontainers
class SearchServiceTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16-alpine");

    private static final String SHA = "e".repeat(40);

    @Autowired GraphRepository repository;
    @Autowired SearchService search;

    @BeforeEach
    void seed() {
        if (repository.exists(SHA)) return;
        List<ParsedFile> parsed = List.of(
                new ParsedFile("src/app/Service.java", "java", "app",
                        List.of(new RawImport("app.Repo", false, false)),
                        List.of(new Symbol("Service", "CLASS", 3), new Symbol("run", "METHOD", 5)),
                        40, 3, 12, true),
                new ParsedFile("src/app/Repo.java", "java", "app", List.of(),
                        List.of(new Symbol("repoCount", "METHOD", 6), new Symbol("Repo", "INTERFACE", 3)),
                        10, 0, 4, true));
        repository.save(SHA, "o", "r", new GraphBuilder().build(parsed, false), parsed);
    }

    @Test
    void exactMatchRanksBeforePrefixMatch() {
        Results r = search.search(SHA, "repo");
        assertEquals(List.of("Repo", "repoCount"), r.symbols().stream().map(SymbolHit::name).toList());
        assertEquals(List.of("src/app/Repo.java"), r.files().stream().map(FileHit::path).toList());
    }

    @Test
    void matchesFilesAndSymbolsCaseInsensitively() {
        Results r = search.search(SHA, "SERV");
        assertEquals(List.of("src/app/Service.java"), r.files().stream().map(FileHit::path).toList());
        SymbolHit hit = r.symbols().get(0);
        assertEquals("Service", hit.name());
        assertEquals(3, hit.line());
        assertEquals("src/app/Service.java", hit.path());
    }

    @Test
    void likeWildcardsAreTreatedAsLiterals() {
        assertTrue(search.search(SHA, "%").files().isEmpty());
        assertTrue(search.search(SHA, "_").symbols().isEmpty());
    }

    @Test
    void blankAndUnknownQueriesReturnNothing() {
        assertTrue(search.search(SHA, "  ").symbols().isEmpty());
        assertTrue(search.search(SHA, "zzz").files().isEmpty());
        assertTrue(search.search("f".repeat(40), "service").files().isEmpty());
    }
}