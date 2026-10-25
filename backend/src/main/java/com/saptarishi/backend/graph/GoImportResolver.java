package com.saptarishi.backend.graph;

import com.saptarishi.backend.parse.GoModSupport;
import com.saptarishi.backend.parse.ParsedFile;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Turns a Go import path into repository files.
 *
 * <p>A Go import names a package, which is a folder, not a file. An import path starts with the module
 * path declared in go.mod; what follows is the folder, relative to the folder holding that go.mod. All
 * non-test files directly in that folder are the package, so an import links to each of them (at most
 * {@link #MAX_FILES_PER_PACKAGE}, in path order). Standard-library and third-party imports match no module
 * in the repository and resolve to nothing. If a repository has several go.mod files, the longest
 * matching module path wins.
 */
public final class GoImportResolver {

    static final int MAX_FILES_PER_PACKAGE = 50;

    private record Module(String path, String dir) {}

    private final List<Module> modules = new ArrayList<>();
    private final Map<String, List<String>> filesByDir = new HashMap<>();

    public GoImportResolver(Collection<ParsedFile> files) {
        for (ParsedFile f : files) {
            if (GoModSupport.LANGUAGE.equals(f.language()) && !f.packageName().isEmpty()) {
                modules.add(new Module(f.packageName(), dirOf(f.path())));
            } else if ("go".equals(f.language()) && !f.path().endsWith("_test.go")) {
                filesByDir.computeIfAbsent(dirOf(f.path()), d -> new ArrayList<>()).add(f.path());
            }
        }
        modules.sort(Comparator.comparingInt((Module m) -> m.path().length()).reversed());
        filesByDir.values().forEach(list -> list.sort(Comparator.naturalOrder()));
    }

    /** The files of the package that {@code importPath} names, or an empty list. Never includes fromPath. */
    public List<String> resolve(String fromPath, String importPath) {
        for (Module m : modules) {
            if (!importPath.equals(m.path()) && !importPath.startsWith(m.path() + "/")) continue;
            String rel = importPath.substring(m.path().length());
            if (rel.startsWith("/")) rel = rel.substring(1);
            String dir = m.dir().isEmpty() ? rel : rel.isEmpty() ? m.dir() : m.dir() + "/" + rel;
            List<String> files = filesByDir.getOrDefault(dir, List.of());
            return files.stream()
                    .filter(p -> !p.equals(fromPath))
                    .limit(MAX_FILES_PER_PACKAGE)
                    .toList();
        }
        return List.of();
    }

    private static String dirOf(String path) {
        int slash = path.lastIndexOf('/');
        return slash < 0 ? "" : path.substring(0, slash);
    }
}
