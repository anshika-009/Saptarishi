package com.saptarishi.backend.pathfinder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.saptarishi.backend.graph.GraphBuilder.Graph;
import com.saptarishi.backend.graph.GraphBuilder.GraphEdge;
import com.saptarishi.backend.graph.GraphBuilder.GraphNode;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class StartersTest {

    private static GraphNode java(String id, int complexity, int loc) {
        return new GraphNode(id, "", "java", complexity, 0, loc, true);
    }

    private static GraphEdge edge(String a, String b) {
        return new GraphEdge(a, b, "IMPORT");
    }

    private static List<String> pick(Set<String> gods, Set<String> hot) {
        List<GraphNode> nodes = new ArrayList<>();
        List<GraphEdge> edges = new ArrayList<>();
        for (int i = 0; i < 40; i++) nodes.add(java("src/main/F" + i + ".java", 6, 30));
        nodes.add(java("src/main/Core.java", 8, 60));
        for (int i = 0; i < 12; i++) edges.add(edge("src/main/F" + i + ".java", "src/main/Core.java"));
        nodes.add(java("src/main/Tiny.java", 1, 40));
        nodes.add(java("src/main/Short.java", 6, 10));
        nodes.add(java("src/main/Big.java", 30, 300));
        nodes.add(java("src/main/Hot.java", 6, 40));
        nodes.add(java("src/main/Cx.java", 20, 60));
        nodes.add(java("src/main/CyA.java", 6, 40));
        nodes.add(java("src/main/CyB.java", 6, 40));
        edges.add(edge("src/main/CyA.java", "src/main/CyB.java"));
        edges.add(edge("src/main/CyB.java", "src/main/CyA.java"));
        nodes.add(java("src/test/CoreTest.java", 6, 40));
        for (int i = 0; i < 20; i++) edges.add(edge("src/test/CoreTest.java", "src/main/F" + i + ".java"));
        nodes.add(new GraphNode("web/app.rb", "web", "ruby", 9, 0, 60, true));
        return Starters.pick(new Graph(nodes, edges), gods, hot).stream().map(Starters.Starter::path).toList();
    }

    @Test
    void excludesRiskyAndUnsuitableFiles() {
        var ids = pick(Set.of("src/main/Big.java"), Set.of("src/main/Hot.java"));
        assertFalse(ids.contains("src/main/Core.java"));   // 12 dependents
        assertFalse(ids.contains("src/main/Tiny.java"));   // no real logic
        assertFalse(ids.contains("src/main/Short.java"));  // too short
        assertFalse(ids.contains("src/main/Big.java"));    // god file
        assertFalse(ids.contains("src/main/Hot.java"));    // changed often
        assertFalse(ids.contains("src/main/Cx.java"));     // too complex
        assertFalse(ids.contains("src/main/CyA.java"));    // circular import
        assertTrue(ids.stream().noneMatch(p -> p.contains("Test") || p.endsWith(".rb")));
    }

    @Test
    void testFilesDoNotMakeAClassLookRisky() {
        var ids = pick(Set.of(), Set.of());
        assertTrue(ids.contains("src/main/F20.java"));
        assertEquals(15, ids.size());
    }
}