package com.saptarishi.backend.graph;

import com.saptarishi.backend.graph.GraphBuilder.Graph;
import com.saptarishi.backend.graph.GraphBuilder.GraphEdge;
import com.saptarishi.backend.graph.GraphBuilder.GraphNode;
import com.saptarishi.backend.parse.ParsedFile;
import com.saptarishi.backend.parse.ParsedFile.Symbol;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class GraphRepository {

    private static final int BATCH = 1000;

    /**
     * Bump this when the parsers or the call graph change what is stored for a commit. Version 2: call
     * graph for every language with a parser, not only Java.
     */
    public static final int ANALYSIS_VERSION = 2;

    private record SymbolRow(String path, Symbol symbol) {}

    private final JdbcTemplate jdbc;

    public GraphRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public boolean exists(String sha) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "select exists(select 1 from repo_snapshot where sha = ?)", Boolean.class, sha));
    }

    /**
     * Deletes the snapshot of this commit if an older analysis made it, so that it is analysed again.
     * Everything stored for it goes with it (files, symbols, edges, calls, churn).
     */
    @Transactional
    public boolean evictIfStale(String sha) {
        return jdbc.update("delete from repo_snapshot where sha = ? and analysis_version < ?",
                sha, ANALYSIS_VERSION) > 0;
    }

    /** Insert-if-absent, all or nothing. Returns false if this SHA was already stored. */
    @Transactional
    public boolean save(String sha, String owner, String repo, Graph graph, List<ParsedFile> parsed) {
        int inserted = jdbc.update("""
                insert into repo_snapshot (sha, owner, repo, file_count, edge_count, analysis_version)
                values (?, ?, ?, ?, ?, ?) on conflict (sha) do nothing""",
                sha, owner, repo, graph.nodes().size(), graph.edges().size(), ANALYSIS_VERSION);
        if (inserted == 0) return false;

        jdbc.batchUpdate("""
                insert into source_file (sha, path, folder, language, tokens, complexity, loc, parsed)
                values (?, ?, ?, ?, ?, ?, ?, ?)""",
                graph.nodes(), BATCH, (ps, n) -> {
                    ps.setString(1, sha);
                    ps.setString(2, n.id());
                    ps.setString(3, n.folder());
                    ps.setString(4, n.language());
                    ps.setInt(5, n.tokens());
                    ps.setInt(6, n.complexity());
                    ps.setInt(7, n.loc());
                    ps.setBoolean(8, n.parsed());
                });

        Set<String> kept = graph.nodes().stream().map(GraphNode::id).collect(Collectors.toSet());
        List<SymbolRow> symbols = parsed.stream()
                .filter(p -> kept.contains(p.path()))
                .flatMap(p -> p.symbols().stream().map(s -> new SymbolRow(p.path(), s)))
                .toList();
        jdbc.batchUpdate("insert into symbol (sha, path, name, kind, line) values (?, ?, ?, ?, ?)",
                symbols, BATCH, (ps, r) -> {
                    ps.setString(1, sha);
                    ps.setString(2, r.path());
                    ps.setString(3, r.symbol().name());
                    ps.setString(4, r.symbol().kind());
                    ps.setInt(5, r.symbol().line());
                });

        jdbc.batchUpdate("insert into edge (sha, source, target, type) values (?, ?, ?, ?)",
                graph.edges(), BATCH, (ps, e) -> {
                    ps.setString(1, sha);
                    ps.setString(2, e.source());
                    ps.setString(3, e.target());
                    ps.setString(4, e.type());
                });
        return true;
    }

    @Transactional(readOnly = true)
    public Optional<Graph> load(String sha) {
        if (!exists(sha)) return Optional.empty();
        List<GraphNode> nodes = jdbc.query("""
                select path, folder, language, complexity, tokens, loc, parsed
                from source_file where sha = ? order by path""",
                (rs, i) -> new GraphNode(rs.getString("path"), rs.getString("folder"),
                        rs.getString("language"), rs.getInt("complexity"), rs.getInt("tokens"),
                        rs.getInt("loc"), rs.getBoolean("parsed")),
                sha);
        List<GraphEdge> edges = jdbc.query(
                "select source, target, type from edge where sha = ? order by source, target",
                (rs, i) -> new GraphEdge(rs.getString("source"), rs.getString("target"), rs.getString("type")),
                sha);
        return Optional.of(new Graph(nodes, edges));
    }
}