package com.saptarishi.backend.search;

import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class SearchService {

    private static final int FILE_LIMIT = 8;
    private static final int SYMBOL_LIMIT = 12;
    private static final int MAX_QUERY = 100;

    public record FileHit(String path, String language) {}
    public record SymbolHit(String name, String kind, int line, String path) {}
    public record Results(List<FileHit> files, List<SymbolHit> symbols) {}

    private static final Results EMPTY = new Results(List.of(), List.of());

    private final JdbcTemplate jdbc;

    public SearchService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Case-insensitive substring match; exact and prefix matches rank first. LIKE wildcards in the query are literal. */
    public Results search(String sha, String query) {
        String q = query == null ? "" : query.trim().toLowerCase();
        if (q.isEmpty() || q.length() > MAX_QUERY) return EMPTY;

        String escaped = q.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
        String contains = "%" + escaped + "%";
        String prefix = escaped + "%";

        List<FileHit> files = jdbc.query("""
                select path, language from source_file
                where sha = ? and lower(path) like ? escape '\\'
                order by (lower(substring(path from '[^/]*$')) like ? escape '\\') desc, length(path), path
                limit ?""",
                (rs, i) -> new FileHit(rs.getString(1), rs.getString(2)),
                sha, contains, prefix, FILE_LIMIT);

        List<SymbolHit> symbols = jdbc.query("""
                select name, kind, line, path from symbol
                where sha = ? and lower(name) like ? escape '\\'
                order by (lower(name) = ?) desc, (lower(name) like ? escape '\\') desc, length(name), name, path
                limit ?""",
                (rs, i) -> new SymbolHit(rs.getString(1), rs.getString(2), rs.getInt(3), rs.getString(4)),
                sha, contains, q, prefix, SYMBOL_LIMIT);

        return new Results(files, symbols);
    }
}