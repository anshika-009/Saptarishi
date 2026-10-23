package com.saptarishi.backend.pathfinder;

import com.saptarishi.backend.graph.GraphBuilder.GraphEdge;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Who is affected if a file changes: every file that imports or references it, directly or through others. */
public final class BlastRadius {

    private final Map<String, Set<String>> importers = new HashMap<>();

    public BlastRadius(List<GraphEdge> edges) {
        for (GraphEdge e : edges) {
            if (!e.source().equals(e.target())) {
                importers.computeIfAbsent(e.target(), k -> new LinkedHashSet<>()).add(e.source());
            }
        }
    }

    /** Transitive dependents, excluding the file itself. Safe on cycles. */
    public Set<String> dependents(String path) {
        Set<String> seen = new LinkedHashSet<>();
        Deque<String> queue = new ArrayDeque<>();
        queue.add(path);
        while (!queue.isEmpty()) {
            for (String s : importers.getOrDefault(queue.poll(), Set.of())) {
                if (!s.equals(path) && seen.add(s)) queue.add(s);
            }
        }
        return seen;
    }

    public int direct(String path) {
        return importers.getOrDefault(path, Set.of()).size();
    }
}