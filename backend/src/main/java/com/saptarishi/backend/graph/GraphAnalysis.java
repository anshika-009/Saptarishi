package com.saptarishi.backend.graph;

import com.saptarishi.backend.graph.GraphBuilder.GraphEdge;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Graph algorithms over the stored dependency graph. Pure functions, no database or Spring.
 *
 * - Circular imports: strongly connected components (Tarjan). Any component with more than one file is a cycle.
 * - Blast radius of a file: how many files depend on it, directly or indirectly (reverse breadth-first search).
 */
public final class GraphAnalysis {

    private GraphAnalysis() {}

    public record NodeMetrics(String id, int fanIn, int fanOut, int blastRadius, boolean inCycle) {}

    /** cycles: each is a list of file paths that all depend on each other. Largest first. */
    public record Result(List<List<String>> cycles, List<NodeMetrics> nodes) {}

    public static Result analyze(List<String> ids, List<GraphEdge> edges) {
        int n = ids.size();
        Map<String, Integer> index = new HashMap<>(n * 2);
        for (int i = 0; i < n; i++) index.put(ids.get(i), i);

        List<List<Integer>> out = new ArrayList<>(n);
        List<List<Integer>> in = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            out.add(new ArrayList<>());
            in.add(new ArrayList<>());
        }
        Set<Long> seen = new HashSet<>();
        for (GraphEdge e : edges) {
            Integer s = index.get(e.source());
            Integer t = index.get(e.target());
            if (s == null || t == null || s.equals(t)) continue;
            if (!seen.add((long) s * n + t)) continue; // one edge per pair, whatever its type
            out.get(s).add(t);
            in.get(t).add(s);
        }

        boolean[] inCycle = new boolean[n];
        List<List<String>> cycles = new ArrayList<>();
        for (List<Integer> comp : stronglyConnected(out, n)) {
            if (comp.size() < 2) continue;
            List<String> names = new ArrayList<>(comp.size());
            for (int v : comp) {
                inCycle[v] = true;
                names.add(ids.get(v));
            }
            names.sort(Comparator.naturalOrder());
            cycles.add(names);
        }
        cycles.sort(Comparator.<List<String>>comparingInt(List::size).reversed()
                .thenComparing(c -> c.get(0)));

        int[] mark = new int[n];
        int[] queue = new int[n];
        List<NodeMetrics> nodes = new ArrayList<>(n);
        for (int v = 0; v < n; v++) {
            int stamp = v + 1;
            int head = 0, tail = 0;
            queue[tail++] = v;
            mark[v] = stamp;
            while (head < tail) {
                int u = queue[head++];
                for (int p : in.get(u)) {
                    if (mark[p] != stamp) {
                        mark[p] = stamp;
                        queue[tail++] = p;
                    }
                }
            }
            nodes.add(new NodeMetrics(ids.get(v), in.get(v).size(), out.get(v).size(), tail - 1, inCycle[v]));
        }
        return new Result(cycles, nodes);
    }

    /** Tarjan's algorithm, iterative- so very deep import chains cannot overflow the stack. */
    private static List<List<Integer>> stronglyConnected(List<List<Integer>> adj, int n) {
        int[] idx = new int[n];
        Arrays.fill(idx, -1);
        int[] low = new int[n];
        int[] next = new int[n];
        boolean[] onStack = new boolean[n];
        int[] call = new int[n];
        Deque<Integer> stack = new ArrayDeque<>();
        List<List<Integer>> comps = new ArrayList<>();
        int counter = 0;

        for (int root = 0; root < n; root++) {
            if (idx[root] != -1) continue;
            int sp = 0;
            call[sp++] = root;
            idx[root] = low[root] = counter++;
            stack.push(root);
            onStack[root] = true;

            while (sp > 0) {
                int v = call[sp - 1];
                List<Integer> a = adj.get(v);
                if (next[v] < a.size()) {
                    int w = a.get(next[v]++);
                    if (idx[w] == -1) {
                        idx[w] = low[w] = counter++;
                        stack.push(w);
                        onStack[w] = true;
                        call[sp++] = w;
                    } else if (onStack[w]) {
                        low[v] = Math.min(low[v], idx[w]);
                    }
                } else {
                    if (low[v] == idx[v]) {
                        List<Integer> comp = new ArrayList<>();
                        int w;
                        do {
                            w = stack.pop();
                            onStack[w] = false;
                            comp.add(w);
                        } while (w != v);
                        comps.add(comp);
                    }
                    sp--;
                    if (sp > 0) {
                        int parent = call[sp - 1];
                        low[parent] = Math.min(low[parent], low[v]);
                    }
                }
            }
        }
        return comps;
    }
}