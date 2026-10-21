package com.saptarishi.backend.graph;

import static org.junit.jupiter.api.Assertions.*;

import com.saptarishi.backend.graph.GraphBuilder.Graph;
import com.saptarishi.backend.parse.ParsedFile;
import com.saptarishi.backend.parse.ParsedFile.RawImport;
import com.saptarishi.backend.parse.ParsedFile.Symbol;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@SpringBootTest
@Testcontainers
class GraphRepositoryTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16-alpine");

    @Autowired GraphRepository repository;
    @Autowired JdbcTemplate jdbc;

    private static final String SHA = "a".repeat(40);

    private static final ParsedFile SERVICE = new ParsedFile("src/app/Service.java", "java", "app",
            List.of(new RawImport("app.Repo", false, false)),
            List.of(new Symbol("Service", "CLASS", 3), new Symbol("run", "METHOD", 5)),
            40, 3, 12, true);
    private static final ParsedFile REPO = new ParsedFile("src/app/Repo.java", "java", "app",
            List.of(), List.of(new Symbol("Repo", "INTERFACE", 3)), 10, 0, 4, true);

    @Test
    void saveThenLoadRoundTrips() {
        List<ParsedFile> parsed = List.of(SERVICE, REPO);
        Graph graph = new GraphBuilder().build(parsed, false);
        assertEquals(1, graph.edges().size());

        assertFalse(repository.exists(SHA));
        assertTrue(repository.save(SHA, "o", "r", graph, parsed));
        assertFalse(repository.save(SHA, "o", "r", graph, parsed));

        Graph loaded = repository.load(SHA).orElseThrow();
        assertEquals(2, loaded.nodes().size());
        assertEquals("src/app/Service.java", loaded.edges().get(0).source());
        assertEquals("src/app/Repo.java", loaded.edges().get(0).target());
        assertEquals(3, jdbc.queryForObject("select count(*) from symbol where sha = ?", Integer.class, SHA));
    }

    @Test
    void unknownShaIsEmpty() {
        assertTrue(repository.load("b".repeat(40)).isEmpty());
    }
}