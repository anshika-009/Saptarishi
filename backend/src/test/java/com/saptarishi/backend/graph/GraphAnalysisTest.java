package com.saptarishi.backend.graph;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.saptarishi.backend.graph.GraphAnalysis.NodeMetrics;
import com.saptarishi.backend.graph.GraphBuilder.GraphEdge;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class GraphAnalysisTest {

    private static GraphEdge e(String a, String b) {
        return new GraphEdge(a, b, "IMPORT");
    }

    private static Map<String, NodeMetrics> byId(GraphAnalysis.Result r) {
        Map<String, NodeMetrics> m = new HashMap<>();
        r.nodes().forEach(n -> m.put(n.id(), n));
        return m;
    }

    @Test
    void findsCycleAndIgnoresSelfLoop() {
        var r = GraphAnalysis.analyze(List.of("A", "B", "C", "D", "E"),
                List.of(e("A", "B"), e("B", "C"), e("C", "A"), e("D", "A"), e("D", "E"), e("E", "E")));
        assertEquals(List.of(List.of("A", "B", "C")), r.cycles());
        assertTrue(byId(r).get("A").inCycle());
        assertFalse(byId(r).get("E").inCycle());
    }

    @Test
    void blastRadiusCountsTransitiveDependents() {
        var r = GraphAnalysis.analyze(List.of("A", "B", "C", "D", "E"),
                List.of(e("A", "B"), e("B", "C"), e("C", "A"), e("D", "A"), e("D", "E")));
        var m = byId(r);
        assertEquals(3, m.get("A").blastRadius()); // C, B (via C) and D
        assertEquals(2, m.get("A").fanIn());
        assertEquals(1, m.get("A").fanOut());
        assertEquals(0, m.get("D").blastRadius()); // nobody depends on D
    }

    @Test
    void separateCyclesAreReportedSeparately() {
        var r = GraphAnalysis.analyze(List.of("P", "Q", "R", "S"),
                List.of(e("P", "Q"), e("Q", "P"), e("R", "S"), e("S", "R"), e("Q", "R")));
        assertEquals(2, r.cycles().size());
    }

    @Test
    void importAndReferenceBetweenSameFilesCountOnce() {
        var r = GraphAnalysis.analyze(List.of("A", "B"),
                List.of(new GraphEdge("A", "B", "IMPORT"), new GraphEdge("A", "B", "REFERENCE")));
        assertEquals(1, byId(r).get("A").fanOut());
    }

    @Test
    void longChainDoesNotOverflowTheStack() {
        int n = 5000;
        List<String> ids = new ArrayList<>();
        List<GraphEdge> edges = new ArrayList<>();
        for (int i = 0; i < n; i++) ids.add("f" + i);
        for (int i = 0; i < n - 1; i++) edges.add(e("f" + i, "f" + (i + 1)));
        assertTrue(GraphAnalysis.analyze(ids, edges).cycles().isEmpty());
        edges.add(e("f" + (n - 1), "f0"));
        assertEquals(n, GraphAnalysis.analyze(ids, edges).cycles().get(0).size());
    }
}