package com.saptarishi.backend.pathfinder;

import com.saptarishi.backend.graph.GraphAnalysis;
import com.saptarishi.backend.graph.GraphBuilder.Graph;
import com.saptarishi.backend.graph.GraphBuilder.GraphEdge;
import com.saptarishi.backend.graph.GraphBuilder.GraphNode;
import com.saptarishi.backend.graph.HealthScore;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * "Safe starter files": a place to make a first change when there is no suitable issue.
 * A starter has some logic but not too much, very few dependents, and none of the red flags
 * (circular import, god file, frequently changed). Needs no GitHub access.
 */
public final class Starters {

    private Starters() {}

    public record Starter(String path, int dependents, int complexity, int loc, int uses) {}

    static final int MIN_COMPLEXITY = 3;
    static final int MAX_COMPLEXITY = 15;
    static final int MIN_LOC = 20;
    public static final int LIMIT = 15;

    /**
     * Test files are left out, and the blast radius is re-computed without them, so a test that
     * exercises a class does not make that class look risky to change.
     *
     * @param gods files flagged as god files by the health score
     * @param hot  files changed unusually often (empty if churn was never computed)
     */
    public static List<Starter> pick(Graph graph, Set<String> gods, Set<String> hot) {
        List<GraphNode> prod = graph.nodes().stream()
                .filter(n -> !HealthScore.isTestPath(n.id()))
                .toList();
        Set<String> ids = prod.stream().map(GraphNode::id).collect(Collectors.toSet());
        List<GraphEdge> edges = graph.edges().stream()
                .filter(e -> ids.contains(e.source()) && ids.contains(e.target()))
                .toList();
        GraphAnalysis.Result analysis = GraphAnalysis.analyze(prod.stream().map(GraphNode::id).toList(), edges);
        Map<String, GraphAnalysis.NodeMetrics> metrics = new HashMap<>();
        analysis.nodes().forEach(m -> metrics.put(m.id(), m));

        int total = (int) prod.stream().filter(GraphNode::parsed).count();
        return prod.stream()
                .filter(GraphNode::parsed)
                .filter(n -> HealthScore.SCORED_LANGUAGES.contains(n.language()))
                .filter(n -> !HealthScore.isBoilerplate(n.id()))
                .filter(n -> n.complexity() >= MIN_COMPLEXITY && n.complexity() <= MAX_COMPLEXITY)
                .filter(n -> n.loc() >= MIN_LOC)
                .filter(n -> !gods.contains(n.id()) && !hot.contains(n.id()))
                .filter(n -> !metrics.get(n.id()).inCycle())
                .filter(n -> Difficulty.blast(metrics.get(n.id()).blastRadius(), total) == Difficulty.Level.SMALL)
                .map(n -> {
                    GraphAnalysis.NodeMetrics m = metrics.get(n.id());
                    return new Starter(n.id(), m.blastRadius(), n.complexity(), n.loc(), m.fanOut());
                })
                .sorted(Comparator.comparingInt(Starter::dependents)
                        .thenComparingInt(Starter::complexity)
                        .thenComparing(Starter::path))
                .limit(LIMIT)
                .toList();
    }
}