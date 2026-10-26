package com.saptarishi.backend.ingest;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

/** Decides which files are kept. classify() returns a language name, or null to skip the file. */
@Component
public class SourceFilter {

    private static final Set<String> EXCLUDED_DIRS = Set.of(
            ".git", "node_modules", "target", "build", "dist", "vendor", "out",
            ".idea", ".vscode", ".gradle", "__pycache__", "venv", ".venv", "obj");

    private static final Set<String> EXCLUDED_NAMES = Set.of(
            "package-lock.json", "yarn.lock", "pnpm-lock.yaml");

    // Languages we will parse (or plan to).
    private static final Map<String, String> SOURCE = Map.ofEntries(
            Map.entry("java", "java"),
            Map.entry("js", "javascript"), Map.entry("jsx", "javascript"),
            Map.entry("mjs", "javascript"), Map.entry("cjs", "javascript"),
            Map.entry("ts", "typescript"), Map.entry("tsx", "typescript"),
            Map.entry("py", "python"), Map.entry("go", "go"),
            Map.entry("cs", "csharp"), Map.entry("rs", "rust"),
            // ".h" is C here; assignHeaderLanguage() turns it into C++ in a mostly-C++ repository
            Map.entry("c", "c"), Map.entry("h", "c"),
            Map.entry("cpp", "cpp"), Map.entry("cc", "cpp"), Map.entry("cxx", "cpp"), Map.entry("c++", "cpp"),
            Map.entry("hpp", "cpp"), Map.entry("hh", "cpp"), Map.entry("hxx", "cpp"), Map.entry("h++", "cpp"),
            Map.entry("ipp", "cpp"), Map.entry("tpp", "cpp"), Map.entry("inl", "cpp"),
            Map.entry("rb", "ruby"), Map.entry("php", "php"), Map.entry("kt", "kotlin"), Map.entry("kts", "kotlin"));

    // Other text files: kept as plain nodes, not parsed.
    private static final Set<String> TEXT = Set.of(
            "md", "txt", "json", "xml", "yml", "yaml", "properties", "gradle", "toml", "html",
            "css", "scss", "sql", "sh", "swift");

    public String classify(String relPath) {
        String[] segs = relPath.split("/");
        for (int i = 0; i < segs.length - 1; i++) {
            if (EXCLUDED_DIRS.contains(segs[i])) return null;
        }
        String name = segs[segs.length - 1];
        if (EXCLUDED_NAMES.contains(name) || name.endsWith(".min.js")) return null;

        if (name.equals("go.mod")) return "gomod"; // read for its module path, not shown as a source file

        int dot = name.lastIndexOf('.');
        if (dot <= 0) return null; // no extension, or a dotfile
        String ext = name.substring(dot + 1).toLowerCase();

        String lang = SOURCE.get(ext);
        if (lang != null) return lang;
        return TEXT.contains(ext) ? "text" : null;
    }

    /**
     * A {@code .h} file can be C or C++, and the extension does not say which. The repository decides: if it
     * has at least as many C++ files as {@code .c} files, its headers are read as C++ (the C++ grammar also
     * reads C); otherwise they stay C, so a C library with one C++ example is not relabelled. A repository
     * with no C++ files at all is left alone.
     */
    public List<IndexedFile> assignHeaderLanguage(List<IndexedFile> files) {
        long cpp = files.stream().filter(f -> "cpp".equals(f.language())).count();
        long c = files.stream()
                .filter(f -> "c".equals(f.language()) && f.path().toLowerCase(Locale.ROOT).endsWith(".c")).count();
        if (cpp == 0 || cpp < c) return files;
        List<IndexedFile> out = new ArrayList<>(files.size());
        for (IndexedFile f : files) {
            boolean cHeader = "c".equals(f.language()) && f.path().toLowerCase(Locale.ROOT).endsWith(".h");
            out.add(cHeader ? new IndexedFile(f.path(), "cpp", f.size()) : f);
        }
        return out;
    }
}
