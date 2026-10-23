package com.saptarishi.backend.pathfinder;

import static org.junit.jupiter.api.Assertions.*;

import com.saptarishi.backend.graph.GraphBuilder.GraphEdge;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class BlastRadiusTest {

    private final BlastRadius radius = new BlastRadius(List.of(
            new GraphEdge("a", "b", "IMPORT"),
            new GraphEdge("b", "c", "IMPORT"),
            new GraphEdge("d", "c", "IMPORT"),
            new GraphEdge("d", "c", "REFERENCE"),   // same pair, other edge type: counted once
            new GraphEdge("e", "f", "IMPORT"),
            new GraphEdge("f", "e", "IMPORT")));

    @Test
    void dependentsAreTransitive() {
        assertEquals(Set.of("a", "b", "d"), radius.dependents("c"));
        assertEquals(Set.of("a"), radius.dependents("b"));
    }

    @Test
    void aLeafNobodyImportsHasNoBlastRadius() {
        assertTrue(radius.dependents("a").isEmpty());
    }

    @Test
    void directCountsEachImporterOnce() {
        assertEquals(2, radius.direct("c"));
        assertEquals(0, radius.direct("a"));
    }

    @Test
    void cyclesTerminateAndExcludeTheFileItself() {
        assertEquals(Set.of("f"), radius.dependents("e"));
    }
}