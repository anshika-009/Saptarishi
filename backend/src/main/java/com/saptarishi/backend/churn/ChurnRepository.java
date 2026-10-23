package com.saptarishi.backend.churn;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class ChurnRepository {

    private static final int BATCH = 1000;

    public record Origin(String owner, String repo) {}

    private final JdbcTemplate jdbc;

    public ChurnRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<Origin> origin(String sha) {
        List<Origin> rows = jdbc.query("select owner, repo from repo_snapshot where sha = ?",
                (rs, i) -> new Origin(rs.getString("owner"), rs.getString("repo")), sha);
        return rows.stream().findFirst();
    }

    /** Commits examined, or empty if churn was never computed for this snapshot. */
    public Optional<Integer> examined(String sha) {
        List<Integer> rows = jdbc.query(
                "select churn_commits from repo_snapshot where sha = ? and churn_commits is not null",
                (rs, i) -> rs.getInt(1), sha);
        return rows.stream().findFirst();
    }

    public Map<String, Integer> load(String sha) {
        List<Map.Entry<String, Integer>> rows = jdbc.query(
                "select path, commits from file_churn where sha = ?",
                (rs, i) -> Map.entry(rs.getString(1), rs.getInt(2)), sha);
        Map<String, Integer> out = new HashMap<>();
        rows.forEach(e -> out.put(e.getKey(), e.getValue()));
        return out;
    }

    @Transactional
    public void save(String sha, int examined, Map<String, Integer> churn) {
        jdbc.update("delete from file_churn where sha = ?", sha);
        List<Map.Entry<String, Integer>> rows =
                churn.entrySet().stream().filter(e -> e.getValue() > 0).toList();
        jdbc.batchUpdate("insert into file_churn (sha, path, commits) values (?, ?, ?)",
                rows, BATCH, (ps, e) -> {
                    ps.setString(1, sha);
                    ps.setString(2, e.getKey());
                    ps.setInt(3, e.getValue());
                });
        jdbc.update("update repo_snapshot set churn_commits = ? where sha = ?", examined, sha);
    }
}