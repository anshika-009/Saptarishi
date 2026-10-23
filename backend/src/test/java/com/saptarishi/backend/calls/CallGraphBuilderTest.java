package com.saptarishi.backend.calls;

import static org.junit.jupiter.api.Assertions.*;

import com.saptarishi.backend.calls.CallGraph.CallEdge;
import com.saptarishi.backend.parse.JavaLanguageSupport;
import com.saptarishi.backend.parse.ParsedFile;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class CallGraphBuilderTest {

    static final String SERVICE = "src/app/Service.java";
    static final String REPO = "src/app/repo/Repo.java";
    static final String UTIL = "src/app/Util.java";
    static final String OTHER = "src/app/Other.java";

    private static final JavaLanguageSupport PARSER = new JavaLanguageSupport();

    static List<ParsedFile> files() {
        return List.of(
                PARSER.parse(SERVICE, """
                        package app;
                        import app.repo.Repo;
                        class Service {
                            private final Repo repo;
                            private String text;
                            void run(int id) {
                                repo.find(id);
                                helper();
                                Util.clean("x");
                                make().unique(2);
                                make().get(0);
                                text.trim();
                            }
                            void helper() { }
                            Other make() { return null; }
                        }
                        """),
                PARSER.parse(REPO, """
                        package app.repo;
                        public class Repo {
                            public String find(int id) { return audit(id); }
                            String audit(int id) { return ""; }
                        }
                        """),
                PARSER.parse(UTIL, "package app;\nclass Util { static String clean(String s) { return s; } }\n"),
                PARSER.parse(OTHER, "package app;\nclass Other { void unique(int n) { } void get(int i) { } }\n"));
    }

    static String id(String path, String type, String name, int arity) {
        return path + "#" + type + "." + name + "/" + arity;
    }

    private static Optional<CallEdge> edge(CallGraph g, String from, String to) {
        return g.edges().stream().filter(e -> e.caller().equals(from) && e.callee().equals(to)).findFirst();
    }

    private final CallGraph graph = new CallGraphBuilder().build(files());
    private final String run = id(SERVICE, "Service", "run", 1);

    @Test
    void resolvesFieldSameFileAndStaticCallsExactly() {
        for (String callee : List.of(id(REPO, "Repo", "find", 1), id(SERVICE, "Service", "helper", 0),
                id(UTIL, "Util", "clean", 1), id(SERVICE, "Service", "make", 0))) {
            CallEdge e = edge(graph, run, callee).orElseThrow(() -> new AssertionError("missing edge to " + callee));
            assertFalse(e.guess(), callee);
        }
        assertTrue(edge(graph, id(REPO, "Repo", "find", 1), id(REPO, "Repo", "audit", 1)).isPresent());
    }

    @Test
    void unknownReceiverLinksOnlyUniqueNamesAndFlagsThemAsGuesses() {
        CallEdge guessed = edge(graph, run, id(OTHER, "Other", "unique", 1)).orElseThrow();
        assertTrue(guessed.guess());
        assertTrue(edge(graph, run, id(OTHER, "Other", "get", 1)).isEmpty()); // too common to guess
    }

    @Test
    void libraryReceiversNeverBecomeEdges() {
        assertTrue(graph.edges().stream().noneMatch(e -> e.callee().contains("trim")));
    }
}