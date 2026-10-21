package com.saptarishi.backend.ingest;

import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

/** Decides which files are kept. classify() returns a language name, or null to skip the file. */
@Component
public class SourceFilter {

    private static final Set<String> EXCLUDED_DIRS = Set.of(
            ".git", "node_modules", "target", "build", "dist", "vendor", "out",
            ".idea", ".vscode", ".gradle", "__pycache__", "venv", ".venv");

    private static final Set<String> EXCLUDED_NAMES = Set.of(
            "package-lock.json", "yarn.lock", "pnpm-lock.yaml");

    // Languages we will parse (or plan to).
    private static final Map<String, String> SOURCE = Map.ofEntries(
            Map.entry("java", "java"),
            Map.entry("js", "javascript"), Map.entry("jsx", "javascript"),
            Map.entry("mjs", "javascript"), Map.entry("cjs", "javascript"),
            Map.entry("ts", "typescript"), Map.entry("tsx", "typescript"),
            Map.entry("py", "python"));

    // Other text files: kept as plain nodes, not parsed.
    private static final Set<String> TEXT = Set.of(
            "md", "txt", "json", "xml", "yml", "yaml", "properties", "gradle", "toml", "html",
            "css", "scss", "sql", "sh", "kt", "go", "rs", "c", "h", "cpp", "cs", "rb", "php", "swift");

    public String classify(String relPath) {
        String[] segs = relPath.split("/");
        for (int i = 0; i < segs.length - 1; i++) {
            if (EXCLUDED_DIRS.contains(segs[i])) return null;
        }
        String name = segs[segs.length - 1];
        if (EXCLUDED_NAMES.contains(name) || name.endsWith(".min.js")) return null;

        int dot = name.lastIndexOf('.');
        if (dot <= 0) return null; // no extension, or a dotfile
        String ext = name.substring(dot + 1).toLowerCase();

        String lang = SOURCE.get(ext);
        if (lang != null) return lang;
        return TEXT.contains(ext) ? "text" : null;
    }
}