package com.saptarishi.backend.calls;

import static org.junit.jupiter.api.Assertions.*;

import com.saptarishi.backend.calls.CallGraph.MethodNode;
import com.saptarishi.backend.calls.CallRepository.Direction;
import com.saptarishi.backend.calls.CallRepository.FileMethod;
import com.saptarishi.backend.calls.CallRepository.Subgraph;
import com.saptarishi.backend.graph.GraphBuilder;
import com.saptarishi.backend.graph.GraphRepository;
import com.saptarishi.backend.parse.JavaLanguageSupport;
import com.saptarishi.backend.parse.ParsedFile;
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
class CallRepositoryTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16-alpine");

    private static final String SHA = "9".repeat(40);
    private static final String SERVICE = "src/app/Service.java";
    private static final String REPO = "src/app/Repo.java";
    private static final String RUN = SERVICE + "#Service.run/1";
    private static final String FIND = REPO + "#Repo.find/1";
    private static final String AUDIT = REPO + "#Repo.audit/1";
    private static final String HELPER = SERVICE + "#Service.helper/0";

    @Autowired GraphRepository graphs;
    @Autowired CallRepository calls;

    @BeforeEach
    void seed() {
        if (graphs.exists(SHA)) return;
        JavaLanguageSupport parser = new JavaLanguageSupport();
        List<ParsedFile> parsed = List.of(
                parser.parse(SERVICE, """
                        package app;
                        class Service {
                            private final Repo repo;
                            void run(int id) { repo.find(id); helper(); }
                            void helper() { }
                        }
                        """),
                parser.parse(REPO, """
                        package app;
                        class Repo {
                            String find(int id) { return audit(id); }
                            String audit(int id) { return ""; }
                        }
                        """));
        graphs.save(SHA, "o", "r", new GraphBuilder().build(parsed, false), parsed);
        calls.save(SHA, new CallGraphBuilder().build(parsed));
    }

    @Test
    void availabilityIsPerSnapshot() {
        assertTrue(calls.available(SHA));
        assertFalse(calls.available("8".repeat(40)));
    }

    @Test
    void listsMethodsOfAFileWithCallerAndCalleeCounts() {
        List<FileMethod> methods = calls.methodsOf(SHA, SERVICE);
        FileMethod run = methods.stream().filter(m -> m.id().equals(RUN)).findFirst().orElseThrow();
        assertEquals(2, run.callees());
        assertEquals(0, run.callers());
        FileMethod helper = methods.stream().filter(m -> m.id().equals(HELPER)).findFirst().orElseThrow();
        assertEquals(1, helper.callers());
    }

    @Test
    void neighbourhoodFollowsTheRequestedDirectionAndDepth() {
        Subgraph out = calls.neighbourhood(SHA, RUN, 2, Direction.CALLEES);
        assertEquals(List.of(RUN, FIND, HELPER, AUDIT),
                out.nodes().stream().map(MethodNode::id).sorted((a, b) -> order(a) - order(b)).toList());
        assertEquals(3, out.edges().size());
        assertFalse(out.truncated());

        Subgraph one = calls.neighbourhood(SHA, RUN, 1, Direction.CALLEES);
        assertEquals(3, one.nodes().size());

        Subgraph in = calls.neighbourhood(SHA, AUDIT, 1, Direction.CALLERS);
        assertEquals(2, in.nodes().size());
        assertEquals(1, in.edges().size());
    }

    @Test
    void unknownMethodIsEmpty() {
        assertTrue(calls.find(SHA, "nope").isEmpty());
        assertTrue(calls.find(SHA, RUN).isPresent());
    }

    private static int order(String id) {
        return List.of(RUN, FIND, HELPER, AUDIT).indexOf(id);
    }
}