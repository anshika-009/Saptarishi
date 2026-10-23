package com.saptarishi.backend.graph;

import com.saptarishi.backend.graph.GraphBuilder.GraphNode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Churn x complexity. A file is hot when it is both changed often and complex, which is where
 * bugs tend to cluster. Pure function over the stored graph and a churn map.
 */
public final class Hotspots {

    private Hotspots() {}

    /** heat is 0 to 100. commits is how many of the examined commits touched the file. */
    public record FileHeat(String path, int commits, int complexity, int loc, int heat) {}

    /**
     * Same population as the health score: parsed files in scored languages, tests and
     * boilerplate excluded. Ranks are taken only among files that were changed recently and
     * contain logic, so a file does not look hot just because most of the repository is untouched.
     * heat = 100 x percentile(commits) x percentile(complexity); everything else is 0.
     * Sorted hottest first.
     */
    public static List<FileHeat> compute(List<GraphNode> nodes, Map<String, Integer> churn) {
        List<GraphNode> files = nodes.stream()
                .filter(GraphNode::parsed)
                .filter(n -> HealthScore.SCORED_LANGUAGES.contains(n.language()))
                .filter(n -> !HealthScore.isTestPath(n.id()))
                .filter(n -> !HealthScore.isBoilerplate(n.id()))
                .toList();

        List<GraphNode> eligible = files.stream()
                .filter(f -> churn.getOrDefault(f.id(), 0) > 0 && f.complexity() > 0)
                .toList();
        int[] commits = eligible.stream().mapToInt(f -> churn.get(f.id())).toArray();
        int[] cx = eligible.stream().mapToInt(GraphNode::complexity).toArray();
        double[] pCommits = HealthScore.percentiles(commits);
        double[] pCx = HealthScore.percentiles(cx);

        Map<String, Integer> heatOf = new HashMap<>();
        for (int i = 0; i < eligible.size(); i++) {
            heatOf.put(eligible.get(i).id(), (int) Math.round(100 * pCommits[i] * pCx[i]));
        }

        List<FileHeat> out = new ArrayList<>(files.size());
        for (GraphNode f : files) {
            out.add(new FileHeat(f.id(), churn.getOrDefault(f.id(), 0), f.complexity(), f.loc(),
                    heatOf.getOrDefault(f.id(), 0)));
        }
        out.sort(Comparator.comparingInt(FileHeat::heat).reversed().thenComparing(FileHeat::path));
        return out;
    }
}