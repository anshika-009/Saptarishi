package com.saptarishi.backend.graph;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.saptarishi.backend.graph.GraphBuilder.Graph;
import com.saptarishi.backend.graph.GraphBuilder.GraphEdge;
import com.saptarishi.backend.graph.GraphBuilder.GraphNode;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class HealthScoreTest {

    private static GraphNode node(String id, int loc, int complexity) {
        return new GraphNode(id, "", "java", complexity, loc * 8, loc, true);
    }

    private static GraphEdge edge(String from, String to) {
        return new GraphEdge(from, to, "IMPORT");
    }

    private static HealthScore.Factor factor(HealthScore.Result r, String key) {
        return r.factors().stream().filter(f -> f.key().equals(key)).findFirst().orElseThrow();
    }

    /** n small, quiet files in a chain (a -> b -> c ...), no cycles, low complexity. */
    private static Graph healthyChain(int n, String prefix) {
        List<GraphNode> nodes = new ArrayList<>();
        List<GraphEdge> edges = new ArrayList<>();
        for (int i = 0; i < n; i++) nodes.add(node(prefix + "F" + i + ".java", 40, 4));
        for (int i = 0; i < n - 1; i++) edges.add(edge(prefix + "F" + i + ".java", prefix + "F" + (i + 1) + ".java"));
        return new Graph(nodes, edges);
    }

    /** healthyChain plus one big, complex file that 10 others import and that imports 2 more. */
    private static Graph withHub(String hubId) {
        Graph base = healthyChain(30, "src/main/");
        List<GraphNode> nodes = new ArrayList<>(base.nodes());
        List<GraphEdge> edges = new ArrayList<>(base.edges());
        nodes.add(node(hubId, 900, 150));
        for (int i = 0; i < 10; i++) edges.add(edge("src/main/F" + i + ".java", hubId));
        edges.add(edge(hubId, "src/main/F20.java"));
        edges.add(edge(hubId, "src/main/F21.java"));
        return new Graph(nodes, edges);
    }

    @Test
    void percentilesGiveTiesTheMiddleRank() {
        assertArrayEquals(new double[] {0.125, 0.375, 0.625, 0.875},
                HealthScore.percentiles(new int[] {1, 2, 3, 4}), 1e-9);
        assertArrayEquals(new double[] {0.5, 0.5, 0.5, 0.5},
                HealthScore.percentiles(new int[] {7, 7, 7, 7}), 1e-9);
        assertArrayEquals(new double[] {0.25, 0.25, 0.75, 0.75},
                HealthScore.percentiles(new int[] {5, 5, 9, 9}), 1e-9);
    }

    @Test
    void cleanRepoScoresPerfectly() {
        var r = HealthScore.of(healthyChain(20, "src/main/"));
        assertEquals(100, r.score());
        assertEquals("A", r.grade());
        assertTrue(r.godFiles().isEmpty());
        assertEquals(0, factor(r, "cycles").penalty());
    }

    @Test
    void flagsAFileThatIsLargeComplexAndHeavilyConnected() {
        var r = HealthScore.of(withHub("src/main/Hub.java"));

        assertEquals(1, r.godFiles().size());
        var god = r.godFiles().get(0);
        assertEquals("src/main/Hub.java", god.id());
        assertEquals(10, god.fanIn());
        assertEquals(2, god.fanOut());
        assertTrue(god.score() > 80, "score was " + god.score());
        assertEquals(HealthScore.POINTS_PER_GOD_FILE, factor(r, "god").penalty());
    }

    @Test
    void aBigComplexFileNobodyUsesIsNotAGodFile() {
        Graph g = healthyChain(30, "src/main/");
        List<GraphNode> nodes = new ArrayList<>(g.nodes());
        nodes.add(node("src/main/Parser.java", 900, 150)); // huge, but zero connections
        var r = HealthScore.of(new Graph(nodes, g.edges()));
        assertTrue(r.godFiles().isEmpty());
    }

    @Test
    void smallReposDoNotFlagTheirLargestFile() {
        // The biggest file is the top of every percentile, but is far below the absolute floors.
        var nodes = List.of(node("A.java", 120, 20), node("B.java", 30, 3), node("C.java", 20, 2),
                node("D.java", 25, 2), node("E.java", 10, 1));
        var edges = List.of(edge("B.java", "A.java"), edge("C.java", "A.java"), edge("D.java", "A.java"),
                edge("E.java", "A.java"), edge("A.java", "B.java"));
        assertTrue(HealthScore.of(new Graph(nodes, edges)).godFiles().isEmpty());
    }

    @Test
    void testFilesAreExcludedAndTheirImportsDoNotCount() {
        Graph prod = withHub("src/main/Hub.java");
        List<GraphNode> nodes = new ArrayList<>(prod.nodes());
        List<GraphEdge> edges = new ArrayList<>(prod.edges());
        // A huge, connected test file must not become a god file either.
        nodes.add(node("src/test/HubTest.java", 900, 150));
        for (int i = 0; i < 12; i++) edges.add(edge("src/test/HubTest.java", "src/main/F" + i + ".java"));
        edges.add(edge("src/test/HubTest.java", "src/main/Hub.java"));

        var r = HealthScore.of(new Graph(nodes, edges));

        assertEquals(1, r.testFilesExcluded());
        assertEquals(31, r.files());
        assertEquals(1, r.godFiles().size());
        assertEquals("src/main/Hub.java", r.godFiles().get(0).id());
        assertEquals(10, r.godFiles().get(0).fanIn()); // the test file's import is not counted
    }

    @Test
    void circularImportsCostPointsInProportionToHowManyFilesAreInvolved() {
        Graph g = healthyChain(20, "src/main/");
        List<GraphEdge> edges = new ArrayList<>(g.edges());
        edges.add(edge("src/main/F2.java", "src/main/F0.java")); // closes F0 -> F1 -> F2 -> F0
        var r = HealthScore.of(new Graph(g.nodes(), edges));

        var cycles = factor(r, "cycles");
        assertEquals(9, cycles.penalty()); // 3 of 20 files = 15% -> ceil(60 * 0.15)
        assertTrue(cycles.detail().startsWith("3 of 20 files"), cycles.detail());
        assertEquals(100 - 9, r.score());
    }

    @Test
    void cyclePenaltyIsCapped() {
        List<GraphNode> nodes = new ArrayList<>();
        List<GraphEdge> edges = new ArrayList<>();
        for (int i = 0; i < 10; i++) nodes.add(node("src/main/F" + i + ".java", 40, 4));
        for (int i = 0; i < 10; i++) edges.add(edge("src/main/F" + i + ".java", "src/main/F" + ((i + 1) % 10) + ".java"));
        var r = HealthScore.of(new Graph(nodes, edges));
        assertEquals(HealthScore.MAX_CYCLES, factor(r, "cycles").penalty());
    }

    @Test
    void complexityPenaltyGrowsBetweenTheTwoThresholds() {
        assertEquals(0, HealthScore.scale(0.10, 0.15, 0.40, 25));
        assertEquals(0, HealthScore.scale(0.15, 0.15, 0.40, 25));
        assertEquals(13, HealthScore.scale(0.275, 0.15, 0.40, 25));
        assertEquals(25, HealthScore.scale(0.40, 0.15, 0.40, 25));
        assertEquals(25, HealthScore.scale(0.90, 0.15, 0.40, 25));
    }

    @Test
    void denseCodeLosesComplexityPoints() {
        List<GraphNode> nodes = new ArrayList<>();
        for (int i = 0; i < 10; i++) nodes.add(node("src/main/F" + i + ".java", 100, 40)); // 0.40 per line
        var r = HealthScore.of(new Graph(nodes, List.of()));
        assertEquals(HealthScore.MAX_COMPLEXITY, factor(r, "complexity").penalty());
    }

    @Test
    void unparsedAndBoilerplateFilesAreNotScored() {
        List<GraphNode> nodes = new ArrayList<>(healthyChain(10, "src/main/").nodes());
        nodes.add(new GraphNode("src/main/Broken.java", "", "java", 0, 0, 500, false));
        nodes.add(node("src/main/package-info.java", 3, 0));
        var r = HealthScore.of(new Graph(nodes, List.of()));
        assertEquals(10, r.files());
    }

    @Test
    void emptyRepoHasNoScore() {
        var r = HealthScore.of(new Graph(List.of(), List.of()));
        assertEquals("n/a", r.grade());
        assertEquals(0, r.files());
    }

    @Test
    void gradeBoundaries() {
        assertEquals("A", HealthScore.grade(85));
        assertEquals("B", HealthScore.grade(84));
        assertEquals("B", HealthScore.grade(70));
        assertEquals("C", HealthScore.grade(55));
        assertEquals("D", HealthScore.grade(40));
        assertEquals("F", HealthScore.grade(39));
    }

    @Test
    void godFilesAreRankedByScoreAndPenaltyIsCapped() {
        // 200 ordinary files plus seven hubs of increasing size (3.4% of the repo, so all of them
        // are inside the top 5% on every factor). Each hub is imported by the same twelve files.
        List<GraphNode> nodes = new ArrayList<>(healthyChain(200, "src/main/").nodes());
        List<GraphEdge> edges = new ArrayList<>(healthyChain(200, "src/main/").edges());
        for (int h = 0; h < 7; h++) {
            String id = "src/main/Hub" + h + ".java";
            nodes.add(node(id, 900 + h * 50, 150 + h * 10));
            for (int i = 0; i < 12; i++) edges.add(edge("src/main/F" + i + ".java", id));
        }
        var r = HealthScore.of(new Graph(nodes, edges));

        assertEquals(7, r.godFiles().size());
        for (int i = 1; i < r.godFiles().size(); i++) {
            assertTrue(r.godFiles().get(i - 1).score() >= r.godFiles().get(i).score());
        }
        assertEquals("src/main/Hub6.java", r.godFiles().get(0).id()); // biggest and most complex first
        assertEquals(HealthScore.MAX_GOD, factor(r, "god").penalty()); // 7 x 4 = 28, capped at 25
        assertFalse(r.godFiles().stream().anyMatch(g -> !g.id().contains("Hub")));
    }

    @Test
    void atMostTheTopFivePercentOfFilesCanBeGodFiles() {
        // Seven hubs among 67 files is 10%: only the top few can be in the top 5% on every factor.
        List<GraphNode> nodes = new ArrayList<>(healthyChain(60, "src/main/").nodes());
        List<GraphEdge> edges = new ArrayList<>(healthyChain(60, "src/main/").edges());
        for (int h = 0; h < 7; h++) {
            String id = "src/main/Hub" + h + ".java";
            nodes.add(node(id, 900 + h * 50, 150 + h * 10));
            for (int i = 0; i < 12; i++) edges.add(edge("src/main/F" + i + ".java", id));
        }
        var r = HealthScore.of(new Graph(nodes, edges));
        assertTrue(r.godFiles().size() <= Math.ceil(0.05 * r.files()), "found " + r.godFiles().size());
    }

    @Test
    void filesWithoutAParserAreNotScored() {
        List<GraphNode> nodes = new ArrayList<>();
        List<GraphEdge> edges = new ArrayList<>();
        for (int i = 0; i < 10; i++) nodes.add(node("src/main/F" + i + ".java", 40, 4));
        for (int i = 0; i < 10; i++) {
            edges.add(edge("src/main/F" + i + ".java", "src/main/F" + ((i + 1) % 10) + ".java"));
        }
        var javaOnly = HealthScore.of(new Graph(nodes, edges));

        List<GraphNode> mixed = new ArrayList<>(nodes);
        for (int i = 0; i < 40; i++) {
            mixed.add(new GraphNode("web/c" + i + ".js", "web", "javascript", 0, 0, 100, true));
        }
        var withJs = HealthScore.of(new Graph(mixed, edges));

        assertEquals(10, withJs.files());
        assertEquals(javaOnly.score(), withJs.score());
    }
}
