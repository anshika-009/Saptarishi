package com.saptarishi.backend.calls;

import com.saptarishi.backend.calls.CallGraph.CallEdge;
import com.saptarishi.backend.calls.CallGraph.MethodNode;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class CallRepository {

    public enum Direction { CALLERS, CALLEES, BOTH }

    public record FileMethod(String id, String type, String name, int arity, int line,
                             int complexity, int callers, int callees) {}

    public record Subgraph(String root, List<MethodNode> nodes, List<CallEdge> edges, boolean truncated) {}

    private static final int BATCH = 1000;
    public static final int MAX_NODES = 120;

    private final JdbcTemplate jdbc;

    public CallRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Call after the snapshot and its files are saved: methods reference source_file. */
    @Transactional
    public void save(String sha, CallGraph g) {
        jdbc.batchUpdate("""
                insert into method_decl (sha, id, path, type, name, arity, line, complexity)
                values (?, ?, ?, ?, ?, ?, ?, ?)""", g.methods(), BATCH, (ps, m) -> {
            ps.setString(1, sha);
            ps.setString(2, m.id());
            ps.setString(3, m.path());
            ps.setString(4, m.type());
            ps.setString(5, m.name());
            ps.setInt(6, m.arity());
            ps.setInt(7, m.line());
            ps.setInt(8, m.complexity());
        });
        jdbc.batchUpdate("insert into call_edge (sha, caller, callee, calls, guess) values (?, ?, ?, ?, ?)",
                g.edges(), BATCH, (ps, e) -> {
                    ps.setString(1, sha);
                    ps.setString(2, e.caller());
                    ps.setString(3, e.callee());
                    ps.setInt(4, e.calls());
                    ps.setBoolean(5, e.guess());
                });
        jdbc.update("update repo_snapshot set call_graph = true where sha = ?", sha);
    }

    /** False for snapshots analysed before the call graph existed: they need a re-analysis. */
    public boolean available(String sha) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "select coalesce((select call_graph from repo_snapshot where sha = ?), false)",
                Boolean.class, sha));
    }

    public List<FileMethod> methodsOf(String sha, String path) {
        return jdbc.query("""
                select m.id, m.type, m.name, m.arity, m.line, m.complexity,
                       (select count(*) from call_edge e where e.sha = m.sha and e.callee = m.id),
                       (select count(*) from call_edge e where e.sha = m.sha and e.caller = m.id)
                from method_decl m where m.sha = ? and m.path = ? order by m.line, m.id""",
                (rs, i) -> new FileMethod(rs.getString(1), rs.getString(2), rs.getString(3), rs.getInt(4),
                        rs.getInt(5), rs.getInt(6), rs.getInt(7), rs.getInt(8)),
                sha, path);
    }

    public Optional<MethodNode> find(String sha, String id) {
        return load(sha, List.of(id)).stream().findFirst();
    }

    /** Breadth-first from root, up to depth levels, capped at MAX_NODES nodes. */
    public Subgraph neighbourhood(String sha, String root, int depth, Direction dir) {
        Set<String> seen = new LinkedHashSet<>(List.of(root));
        Set<CallEdge> edges = new LinkedHashSet<>();
        Set<String> frontier = Set.of(root);

        for (int d = 0; d < depth && !frontier.isEmpty() && seen.size() < MAX_NODES; d++) {
            Set<String> next = new LinkedHashSet<>();
            if (dir != Direction.CALLERS) {
                for (CallEdge e : edgesOf(sha, "caller", frontier)) {
                    edges.add(e);
                    if (seen.add(e.callee())) next.add(e.callee());
                }
            }
            if (dir != Direction.CALLEES) {
                for (CallEdge e : edgesOf(sha, "callee", frontier)) {
                    edges.add(e);
                    if (seen.add(e.caller())) next.add(e.caller());
                }
            }
            frontier = next;
        }

        List<String> ids = seen.stream().limit(MAX_NODES).toList();
        Set<String> keep = new HashSet<>(ids);
        List<CallEdge> kept = edges.stream()
                .filter(e -> keep.contains(e.caller()) && keep.contains(e.callee())).toList();
        return new Subgraph(root, load(sha, ids), kept, seen.size() >= MAX_NODES);
    }

    private List<CallEdge> edgesOf(String sha, String column, Set<String> ids) {
        List<Object> args = new ArrayList<>();
        args.add(sha);
        args.addAll(ids);
        return jdbc.query("select caller, callee, calls, guess from call_edge where sha = ? and "
                        + column + " in (" + placeholders(ids.size()) + ")",
                (rs, i) -> new CallEdge(rs.getString(1), rs.getString(2), rs.getInt(3), rs.getBoolean(4)),
                args.toArray());
    }

    private List<MethodNode> load(String sha, List<String> ids) {
        List<Object> args = new ArrayList<>();
        args.add(sha);
        args.addAll(ids);
        return jdbc.query("select id, path, type, name, arity, line, complexity from method_decl "
                        + "where sha = ? and id in (" + placeholders(ids.size()) + ")",
                (rs, i) -> new MethodNode(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4),
                        rs.getInt(5), rs.getInt(6), rs.getInt(7)),
                args.toArray());
    }

    private static String placeholders(int n) {
        return String.join(",", Collections.nCopies(n, "?"));
    }
}