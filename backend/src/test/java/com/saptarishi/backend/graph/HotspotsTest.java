package com.saptarishi.backend.graph;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.saptarishi.backend.graph.GraphBuilder.GraphNode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class HotspotsTest {

    private static GraphNode java(String id, int complexity) {
        return new GraphNode(id, "", "java", complexity, 0, 100, true);
    }

    private static List<GraphNode> files() {
        List<GraphNode> nodes = new ArrayList<>();
        for (int i = 0; i < 10; i++) nodes.add(java("src/main/F" + i + ".java", 2 + i));
        nodes.add(java("src/test/FooTest.java", 50));
        nodes.add(new GraphNode("web/app.rb", "web", "ruby", 0, 0, 100, true));
        return nodes;
    }

    private static Hotspots.FileHeat find(List<Hotspots.FileHeat> r, String name) {
        return r.stream().filter(x -> x.path().endsWith(name)).findFirst().orElseThrow();
    }

    @Test
    void scoresOnlyProductionFilesInParsedLanguages() {
        var r = Hotspots.compute(files(), Map.of("src/test/FooTest.java", 99, "web/app.rb", 99));
        assertEquals(10, r.size());
    }

    @Test
    void hottestFileIsBothOftenChangedAndComplex() {
        var churn = Map.of("src/main/F9.java", 30, "src/main/F8.java", 2, "src/main/F0.java", 40);
        var r = Hotspots.compute(files(), churn);
        assertEquals("src/main/F9.java", r.get(0).path());
        assertTrue(find(r, "F0.java").heat() < r.get(0).heat(), "trivial file must be cooler");
        assertTrue(find(r, "F8.java").heat() > 0);
    }

    @Test
    void untouchedFilesAndMissingChurnHaveNoHeat() {
        var r = Hotspots.compute(files(), Map.of("src/main/F9.java", 5));
        assertTrue(r.stream().filter(x -> x.commits() == 0).allMatch(x -> x.heat() == 0));
        assertTrue(Hotspots.compute(files(), Map.of()).stream().allMatch(x -> x.heat() == 0));
    }
}