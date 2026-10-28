package com.saptarishi.backend.graph;

import com.saptarishi.backend.graph.GraphBuilder.Graph;
import com.saptarishi.backend.graph.GraphBuilder.GraphEdge;
import com.saptarishi.backend.graph.GraphBuilder.GraphNode;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;


public final class HealthScore {

    private HealthScore() {}

    /** Every one of the three percentiles must be at least this to be called a god file. */
    public static final double PERCENTILE_CUTOFF = 0.95;
    /** Absolute floors, so tiny repos do not produce god files. */
    public static final int MIN_LOC = 300;
    public static final int MIN_COMPLEXITY = 40;
    public static final int MIN_CONNECTIONS = 10;

    /** Penalty caps. They sum to 100, so the score can reach 0. */
    static final int MAX_CYCLES = 25;
    static final int MAX_GOD = 25;
    static final int MAX_COMPLEXITY = 25;
    static final int MAX_COUPLING = 25;

    // Complexity density (complexity points per non-blank line) and mean fan-out at which the
    // penalty starts and at which it reaches its cap. See the README for how these were chosen.
    static final double DENSITY_OK = 0.15;
    static final double DENSITY_BAD = 0.40;
    static final double FANOUT_OK = 3.0;
    static final double FANOUT_BAD = 10.0;

    /** Points lost per god file. */
    static final int POINTS_PER_GOD_FILE = 4;

    /** Largest list of god files returned. */
    static final int MAX_GOD_FILES = 20;
    /**
     * Only languages that have a parser are scored. A file without a parser has no complexity and
     * no import edges, so counting it would make a tangled repo look healthy. Add a language here
     * when its parser is added.
     */
    public static final Set<String> SCORED_LANGUAGES = Set.of("java", "javascript", "typescript", "python", "go", "csharp", "rust", "c", "cpp", "ruby", "php");

    public record GodFile(String id, int loc, int complexity, int fanIn, int fanOut,
                          double score, double sizePct, double complexityPct, double connectivityPct) {}

    /** One line of the breakdown: how many points were lost, out of how many, and why. */
    public record Factor(String key, String label, int penalty, int max, String detail) {}

    /** files: how many files were scored. testFilesExcluded: test files left out of the analysis. */
    public record Result(int score, String grade, int files, int testFilesExcluded,
                         List<Factor> factors, List<GodFile> godFiles) {}

    /**
     * Scores a stored graph. Test files are left out (their imports would inflate the connectivity
     * of the production classes they exercise), the graph analysis is re-run on what remains, and
     * the result is computed from that.
     */
    public static Result of(Graph graph) {
        List<GraphNode> kept = graph.nodes().stream().filter(n -> !isTestPath(n.id())).toList();
        Set<String> ids = kept.stream().map(GraphNode::id).collect(Collectors.toSet());
        List<GraphEdge> edges = graph.edges().stream()
                .filter(e -> ids.contains(e.source()) && ids.contains(e.target()))
                .toList();
        GraphAnalysis.Result analysis = GraphAnalysis.analyze(kept.stream().map(GraphNode::id).toList(), edges);
        Result r = compute(kept, analysis);
        return new Result(r.score(), r.grade(), r.files(), graph.nodes().size() - kept.size(),
                r.factors(), r.godFiles());
    }

    public static Result compute(List<GraphNode> nodes, GraphAnalysis.Result analysis) {
        Map<String, GraphAnalysis.NodeMetrics> metrics = new HashMap<>();
        for (GraphAnalysis.NodeMetrics m : analysis.nodes()) metrics.put(m.id(), m);

        List<GraphNode> files = nodes.stream()
                .filter(GraphNode::parsed)
                .filter(n -> SCORED_LANGUAGES.contains(n.language()))
                .filter(n -> !isBoilerplate(n.id()))
                .toList();
        int n = files.size();
        if (n == 0) return new Result(0, "n/a", 0, 0, List.of(), List.of());

        int[] loc = new int[n];
        int[] cx = new int[n];
        int[] conn = new int[n];
        int[] fanOut = new int[n];
        long totalLoc = 0, totalCx = 0, totalFanOut = 0;
        int inCycle = 0;
        for (int i = 0; i < n; i++) {
            GraphNode f = files.get(i);
            GraphAnalysis.NodeMetrics m = metrics.get(f.id());
            int in = m == null ? 0 : m.fanIn();
            int out = m == null ? 0 : m.fanOut();
            loc[i] = f.loc();
            cx[i] = f.complexity();
            conn[i] = in + out;
            fanOut[i] = out;
            totalLoc += f.loc();
            totalCx += f.complexity();
            totalFanOut += out;
            if (m != null && m.inCycle()) inCycle++;
        }

        // ---- god files
        double[] pLoc = percentiles(loc);
        double[] pCx = percentiles(cx);
        double[] pConn = percentiles(conn);
        List<GodFile> gods = new ArrayList<>();
        int godCount = 0;
        for (int i = 0; i < n; i++) {
            boolean god = pLoc[i] >= PERCENTILE_CUTOFF && pCx[i] >= PERCENTILE_CUTOFF
                    && pConn[i] >= PERCENTILE_CUTOFF
                    && loc[i] >= MIN_LOC && cx[i] >= MIN_COMPLEXITY && conn[i] >= MIN_CONNECTIONS;
            if (!god) continue;
            godCount++;
            GraphAnalysis.NodeMetrics m = metrics.get(files.get(i).id());
            gods.add(new GodFile(files.get(i).id(), loc[i], cx[i],
                    m == null ? 0 : m.fanIn(), m == null ? 0 : m.fanOut(),
                    round1(100 * pLoc[i] * pCx[i] * pConn[i]),
                    round2(pLoc[i]), round2(pCx[i]), round2(pConn[i])));
        }
        gods.sort(Comparator.comparingDouble(GodFile::score).reversed().thenComparing(GodFile::id));
        List<GodFile> shownGods = gods.size() > MAX_GOD_FILES ? gods.subList(0, MAX_GOD_FILES) : gods;

        // ---- penalties
        List<Factor> factors = new ArrayList<>(4);

        double cycleShare = (double) inCycle / n;
        int cyclePenalty = inCycle == 0 ? 0 : Math.min(MAX_CYCLES, (int) Math.ceil(60 * cycleShare));
        factors.add(new Factor("cycles", "Circular imports", cyclePenalty, MAX_CYCLES,
                inCycle == 0 ? "No circular imports"
                        : inCycle + " of " + n + " files (" + pct(cycleShare) + ") are in "
                        + analysis.cycles().size() + (analysis.cycles().size() == 1 ? " cycle" : " cycles")));

        int godPenalty = Math.min(MAX_GOD, POINTS_PER_GOD_FILE * godCount);
        factors.add(new Factor("god", "God files", godPenalty, MAX_GOD,
                godCount == 0 ? "No file is large, complex and heavily connected at once"
                        : godCount + (godCount == 1 ? " god file" : " god files")));

        double density = totalLoc == 0 ? 0 : (double) totalCx / totalLoc;
        int cxPenalty = scale(density, DENSITY_OK, DENSITY_BAD, MAX_COMPLEXITY);
        factors.add(new Factor("complexity", "Complexity", cxPenalty, MAX_COMPLEXITY,
                String.format("%.2f complexity points per line of code", density)));

        double meanOut = (double) totalFanOut / n;
        int couplePenalty = scale(meanOut, FANOUT_OK, FANOUT_BAD, MAX_COUPLING);
        factors.add(new Factor("coupling", "Coupling", couplePenalty, MAX_COUPLING,
                String.format("each file depends on %.1f others on average", meanOut)));

        int penalty = factors.stream().mapToInt(Factor::penalty).sum();
        int score = Math.max(0, 100 - penalty);
        return new Result(score, grade(score), n, 0, factors, List.copyOf(shownGods));
    }

    /** 0 at or below ok, max at or above bad, linear in between. */
    static int scale(double value, double ok, double bad, int max) {
        if (value <= ok) return 0;
        if (value >= bad) return max;
        return (int) Math.round(max * (value - ok) / (bad - ok));
    }

    static String grade(int score) {
        if (score >= 85) return "A";
        if (score >= 70) return "B";
        if (score >= 55) return "C";
        if (score >= 40) return "D";
        return "F";
    }

    /**
     * Test files, by the conventions of each supported language: a test/, tests/, spec/ or __tests__/ folder,
     * and by name {@code *.test.ts}, {@code test_x.py}, {@code x_test.go}, {@code x_test.rs},
     * {@code FooTest.java/.cs/.php}, {@code foo_spec.rb}, {@code foo_test.cc} and the like.
     */
    public static boolean isTestPath(String id) {
        String path = "/" + id;
        if (path.matches("(?i).*/(tests?|specs?|__tests__|__mocks__)/.*")) return true;
        if (path.matches("(?i).*/[^/]*\\.(unit|integration)?tests?/.*")) return true; // C#: MyApp.Tests/
        String name = path.substring(path.lastIndexOf('/') + 1);
        return name.matches(".+\\.(test|spec)\\.[cm]?[jt]sx?")                                      // JS, TS
                || name.matches("test_.+\\.py") || name.matches(".+_test\\.py")                      // Python
                || name.equals("conftest.py")
                || name.endsWith("_test.go")                                                          // Go
                || name.equals("tests.rs") || name.endsWith("_test.rs") || name.endsWith("_tests.rs") // Rust
                || name.matches("(.*[a-z0-9])?(Tests?|IT)\\.(java|cs|php)")                         // Java, C#, PHP
                || name.matches(".+_(spec|test)\\.rb") || name.matches("test_.+\\.rb")                // Ruby
                || name.matches("(.*[a-z0-9])?Tests?\\.(c|cc|cpp|cxx)")                               // C, C++ (gtest)
                || name.matches(".+_(unit)?tests?\\.(c|cc|cpp|cxx)")
                || name.matches("test_.+\\.(c|cc|cpp|cxx)");
    }

    /** Files that carry no logic and would only distort the percentiles. */
    public static boolean isBoilerplate(String id) {
        String name = id.substring(id.lastIndexOf('/') + 1);
        return id.endsWith("package-info.java") || id.endsWith("module-info.java") || id.endsWith(".d.ts")
                || id.endsWith(".pb.go") || name.endsWith("_generated.go") || name.startsWith("zz_generated")
                || id.endsWith(".Designer.cs") || id.endsWith(".g.cs") || id.endsWith(".g.i.cs")
                || id.endsWith("AssemblyInfo.cs")
                || name.endsWith("_pb2.py") || name.endsWith("_pb2_grpc.py")
                || name.endsWith(".pb.cc") || name.endsWith(".pb.h");
    }

    /**
     * Percentile rank of each value among all values, in [0, 1]. Ties share the middle rank,
     * so a repo where most files have the same value does not push them all to the top.
     */
    static double[] percentiles(int[] values) {
        int n = values.length;
        int[] sorted = values.clone();
        Arrays.sort(sorted);
        double[] out = new double[n];
        for (int i = 0; i < n; i++) {
            int below = lowerBound(sorted, values[i]);
            int upTo = lowerBound(sorted, values[i] + 1);
            out[i] = (below + 0.5 * (upTo - below)) / n;
        }
        return out;
    }

    private static int lowerBound(int[] sorted, int key) {
        int lo = 0, hi = sorted.length;
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (sorted[mid] < key) lo = mid + 1;
            else hi = mid;
        }
        return lo;
    }

    private static String pct(double share) {
        return Math.round(share * 100) + "%";
    }

    private static double round1(double v) { return Math.round(v * 10) / 10.0; }
    private static double round2(double v) { return Math.round(v * 100) / 100.0; }
}
