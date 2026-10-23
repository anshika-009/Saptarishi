package com.saptarishi.backend.pathfinder;

import com.saptarishi.backend.graph.GraphBuilder.GraphEdge;
import com.saptarishi.backend.pathfinder.IssueMapper.Confidence;
import com.saptarishi.backend.pathfinder.IssueSource.Issue;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class PathfinderService {

    public record FileHit(String path, String confidence, String reason, int dependents, int complexity) {}
    public record Blast(int dependents, int direct, String level) {}
    public record IssueView(int number, String title, String url, List<String> labels, int comments,
                            String createdAt, String difficulty, Blast blast, List<FileHit> files) {}
    public record Result(boolean available, String reason, int totalFiles, List<IssueView> issues) {}

    private record Cached(Instant at, Result result) {}
    private record FileRow(String path, int complexity) {}

    private static final Duration TTL = Duration.ofMinutes(30);
    private static final int MAX_ISSUES = 40;
    private static final int MAX_CACHED = 200;

    private final JdbcTemplate jdbc;
    private final IssueSource source;
    private final Map<String, Cached> cache = new ConcurrentHashMap<>();

    public PathfinderService(JdbcTemplate jdbc, IssueSource source) {
        this.jdbc = jdbc;
        this.source = source;
    }

    /** Empty if the snapshot is unknown. GitHub is asked at most once per commit per 30 minutes. */
    public Optional<Result> pathfinder(String sha) {
        Cached hit = cache.get(sha);
        if (hit != null && hit.at().plus(TTL).isAfter(Instant.now())) return Optional.of(hit.result());

        List<String[]> snapshot = jdbc.query("select owner, repo from repo_snapshot where sha = ?",
                (rs, i) -> new String[] {rs.getString(1), rs.getString(2)}, sha);
        if (snapshot.isEmpty()) return Optional.empty();
        String owner = snapshot.get(0)[0];
        if ("upload".equals(owner)) return Optional.of(unavailable("Uploaded archives have no GitHub issues."));

        List<Issue> issues;
        try {
            issues = source.fetch(owner, snapshot.get(0)[1]);
        } catch (IOException e) {
            return Optional.of(unavailable(e.getMessage())); // not cached: retry on the next request
        }

        Result result = rank(sha, issues);
        if (cache.size() >= MAX_CACHED) cache.clear();
        cache.put(sha, new Cached(Instant.now(), result));
        return Optional.of(result);
    }

    private static Result unavailable(String reason) {
        return new Result(false, reason, 0, List.of());
    }

    private Result rank(String sha, List<Issue> issues) {
        Map<String, Integer> complexity = new HashMap<>();
        for (FileRow r : jdbc.query("select path, complexity from source_file where sha = ?",
                (rs, i) -> new FileRow(rs.getString(1), rs.getInt(2)), sha)) {
            complexity.put(r.path(), r.complexity());
        }
        List<IssueMapper.Symbol> symbols = jdbc.query(
                "select name, kind, path from symbol where sha = ? and kind in ('CLASS','INTERFACE','ENUM','RECORD','METHOD')",
                (rs, i) -> new IssueMapper.Symbol(rs.getString(1), rs.getString(2), rs.getString(3)), sha);
        List<GraphEdge> edges = jdbc.query("select source, target, type from edge where sha = ?",
                (rs, i) -> new GraphEdge(rs.getString(1), rs.getString(2), rs.getString(3)), sha);

        IssueMapper mapper = new IssueMapper(List.copyOf(complexity.keySet()), symbols);
        BlastRadius radius = new BlastRadius(edges);
        Map<String, Set<String>> memo = new HashMap<>();
        Function<String, Set<String>> dependents = p -> memo.computeIfAbsent(p, radius::dependents);
        int total = complexity.size();

        List<IssueView> views = issues.stream().map(issue -> {
            List<IssueMapper.Match> matches = mapper.map(issue.title() + "\n" + issue.body());
            List<FileHit> files = matches.stream()
                    .map(m -> new FileHit(m.path(), m.confidence().name(), m.reason(),
                            dependents.apply(m.path()).size(), complexity.getOrDefault(m.path(), 0)))
                    .toList();

            boolean strong = matches.stream().anyMatch(m -> m.confidence() != Confidence.LOW);
            List<FileHit> basis = files.stream().filter(f -> !strong || !f.confidence().equals("LOW")).toList();

            Set<String> affected = new HashSet<>();
            int direct = 0;
            int maxComplexity = 0;
            for (FileHit f : basis) {
                affected.addAll(dependents.apply(f.path()));
                direct = Math.max(direct, radius.direct(f.path()));
                maxComplexity = Math.max(maxComplexity, f.complexity());
            }
            basis.forEach(f -> affected.remove(f.path()));

            Difficulty.Level level = Difficulty.blast(affected.size(), total);
            String difficulty = basis.isEmpty() ? "UNKNOWN" : Difficulty.rate(level, maxComplexity);
            Blast blast = basis.isEmpty() ? new Blast(0, 0, "UNKNOWN") : new Blast(affected.size(), direct, level.name());
            return new IssueView(issue.number(), issue.title(), issue.url(), issue.labels(), issue.comments(),
                    issue.createdAt(), difficulty, blast, files);
        }).toList();

        List<IssueView> sorted = views.stream()
                .sorted(Comparator.<IssueView>comparingInt(v -> Difficulty.order(v.difficulty()))
                        .thenComparing(v -> !isBeginnerLabelled(v))
                        .thenComparingInt(v -> v.blast().dependents())
                        .thenComparing(Comparator.comparingInt(IssueView::number).reversed()))
                .limit(MAX_ISSUES)
                .toList();
        return new Result(true, null, total, sorted);
    }

    private static boolean isBeginnerLabelled(IssueView v) {
        return v.labels().stream().map(String::toLowerCase)
                .anyMatch(l -> l.contains("good first") || l.contains("good-first"));
    }
}