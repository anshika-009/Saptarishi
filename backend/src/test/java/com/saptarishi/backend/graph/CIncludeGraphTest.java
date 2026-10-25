package com.saptarishi.backend.graph;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.saptarishi.backend.graph.GraphBuilder.Graph;
import com.saptarishi.backend.graph.GraphBuilder.GraphEdge;
import com.saptarishi.backend.parse.ParsedFile;
import com.saptarishi.backend.parse.ParsedFile.RawImport;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

class CIncludeGraphTest {

    private static ParsedFile file(String path, String language, String... includes) {
        return new ParsedFile(path, language, "",
                Arrays.stream(includes).map(i -> new RawImport(i, false, false)).toList(),
                List.of(), 10, 3, 20, true);
    }

    @Test
    void includesBecomeImportEdgesBetweenCAndHeaderFiles() {
        Graph g = new GraphBuilder().build(List.of(
                file("src/main.c", "c", "<stdio.h>", "\"util.h\"", "\"net/socket.h\""),
                file("src/util.h", "c"),
                file("src/net/socket.h", "c", "\"../util.h\"")), false);

        assertEquals(3, g.nodes().size());
        assertTrue(g.edges().contains(new GraphEdge("src/main.c", "src/util.h", "IMPORT")));
        assertTrue(g.edges().contains(new GraphEdge("src/main.c", "src/net/socket.h", "IMPORT")));
        assertTrue(g.edges().contains(new GraphEdge("src/net/socket.h", "src/util.h", "IMPORT")));
        assertEquals(3, g.edges().size());
    }

    @Test
    void cppSourcesAndHeadersAreLinkedAndTheSameHeaderIsLinkedOnce() {
        Graph g = new GraphBuilder().build(List.of(
                file("app/main.cpp", "cpp", "\"engine.hpp\"", "\"engine.hpp\"", "<vector>"),
                file("app/engine.hpp", "cpp")), false);
        assertEquals(List.of(new GraphEdge("app/main.cpp", "app/engine.hpp", "IMPORT")), g.edges());
    }

    @Test
    void cAndCppFilesCanIncludeEachOther() {
        Graph g = new GraphBuilder().build(List.of(
                file("lib/wrapper.cpp", "cpp", "\"core.h\""),
                file("lib/core.h", "c")), false);
        assertEquals(List.of(new GraphEdge("lib/wrapper.cpp", "lib/core.h", "IMPORT")), g.edges());
    }

    @Test
    void includeCyclesAreKeptAsEdges() {
        Graph g = new GraphBuilder().build(List.of(
                file("a.h", "c", "\"b.h\""),
                file("b.h", "c", "\"a.h\"")), false);
        assertEquals(2, g.edges().size());
    }

    @Test
    void unparsedFilesGetNoEdges() {
        ParsedFile broken = ParsedFile.unparsed("src/bad.c", "c", 5);
        Graph g = new GraphBuilder().build(List.of(broken, file("src/util.h", "c")), false);
        assertTrue(g.edges().isEmpty());
    }
}
