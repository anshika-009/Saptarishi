package com.saptarishi.backend.graph;

import com.saptarishi.backend.parse.GoModSupport;
import com.saptarishi.backend.parse.ParsedFile;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;

@Service
public class GraphBuilder {

    /** Same fields the frontend already reads, plus loc and parsed. */
    public record GraphNode(String id, String folder, String language, int complexity,
                            int tokens, int loc, boolean parsed) {}
    /** type: IMPORT (explicit import) or REFERENCE (same-package use, no import needed). */
    public record GraphEdge(String source, String target, String type) {}
    public record Graph(List<GraphNode> nodes, List<GraphEdge> edges) {

        /**
         * For each file, the files it imports or references. The call graph uses this to decide which files a
         * call can reach, so every language's own import rules apply without being repeated there.
         */
        public Map<String, Set<String>> dependencies() {
            Map<String, Set<String>> deps = new HashMap<>();
            for (GraphEdge e : edges) {
                deps.computeIfAbsent(e.source(), k -> new LinkedHashSet<>()).add(e.target());
            }
            return deps;
        }
    }

    public Graph build(List<ParsedFile> files, boolean includeText) {
        List<ParsedFile> kept = files.stream()
                .filter(f -> (includeText || !"text".equals(f.language())) && !GoModSupport.LANGUAGE.equals(f.language()))
                .toList();

        Set<String> ids = new HashSet<>();
        List<GraphNode> nodes = new ArrayList<>(kept.size());
        for (ParsedFile f : kept) {
            ids.add(f.path());
            nodes.add(new GraphNode(f.path(), folderOf(f.path()), f.language(),
                    f.complexity(), f.tokens(), f.loc(), f.parsed()));
        }

        JavaImportResolver java = new JavaImportResolver(files);
        List<String> allPaths = files.stream().map(ParsedFile::path).toList();
        ScriptImportResolver script = new ScriptImportResolver(allPaths);
        PythonImportResolver python = new PythonImportResolver(allPaths);
        GoImportResolver go = new GoImportResolver(files);
        CSharpImportResolver csharp = new CSharpImportResolver(files);
        RustImportResolver rust = new RustImportResolver(allPaths);
        CIncludeResolver cInclude = new CIncludeResolver(allPaths);
        HtmlImportResolver html = new HtmlImportResolver(allPaths);
        FileRequireResolver rubyFiles = new FileRequireResolver(allPaths, "rb");
        FileRequireResolver phpFiles = new FileRequireResolver(allPaths, "php");
        CSharpImportResolver phpTypes = new CSharpImportResolver(files, "php");
        List<GraphEdge> edges = new ArrayList<>();
        for (ParsedFile f : kept) {
            if (!f.parsed()) continue;
            switch (f.language()) {
                case "java" -> {
                    Set<String> imported = java.resolve(f);
                    for (String target : imported) {
                        if (ids.contains(target)) edges.add(new GraphEdge(f.path(), target, "IMPORT"));
                    }
                    for (String target : java.resolveReferences(f)) {
                        if (!imported.contains(target) && ids.contains(target)) {
                            edges.add(new GraphEdge(f.path(), target, "REFERENCE"));
                        }
                    }
                }
                case "javascript", "typescript" -> {
                    Set<String> targets = new LinkedHashSet<>();
                    for (ParsedFile.RawImport i : f.imports()) script.resolve(f.path(), i.name()).ifPresent(targets::add);
                    for (String t : targets) if (ids.contains(t)) edges.add(new GraphEdge(f.path(), t, "IMPORT"));
                }
                case "python" -> {
                    Set<String> targets = new LinkedHashSet<>();
                    for (ParsedFile.RawImport i : f.imports()) python.resolve(f.path(), i.name()).ifPresent(targets::add);
                    for (String t : targets) if (ids.contains(t)) edges.add(new GraphEdge(f.path(), t, "IMPORT"));
                }
                case "go" -> {
                    Set<String> targets = new LinkedHashSet<>();
                    for (ParsedFile.RawImport i : f.imports()) targets.addAll(go.resolve(f.path(), i.name()));
                    for (String t : targets) if (ids.contains(t)) edges.add(new GraphEdge(f.path(), t, "IMPORT"));
                }
                case "csharp" -> {
                    Set<String> imported = csharp.resolve(f);
                    for (String t : imported) if (ids.contains(t)) edges.add(new GraphEdge(f.path(), t, "IMPORT"));
                    for (String t : csharp.resolveReferences(f)) {
                        if (!imported.contains(t) && ids.contains(t)) edges.add(new GraphEdge(f.path(), t, "REFERENCE"));
                    }
                }
                case "rust" -> {
                    Set<String> targets = new LinkedHashSet<>();
                    for (ParsedFile.RawImport i : f.imports()) rust.resolve(f.path(), i.name()).ifPresent(targets::add);
                    for (String t : targets) if (ids.contains(t)) edges.add(new GraphEdge(f.path(), t, "IMPORT"));
                }
                case "ruby" -> {
                    Set<String> targets = new LinkedHashSet<>();
                    for (ParsedFile.RawImport i : f.imports()) rubyFiles.resolve(f.path(), i.name()).ifPresent(targets::add);
                    for (String t : targets) if (ids.contains(t)) edges.add(new GraphEdge(f.path(), t, "IMPORT"));
                }
                case "php" -> {
                    Set<String> targets = new LinkedHashSet<>(phpTypes.resolve(f));
                    for (ParsedFile.RawImport i : f.imports()) {
                        if (i.name().startsWith("file:")) phpFiles.resolve(f.path(), i.name().substring(5)).ifPresent(targets::add);
                    }
                    for (String t : targets) if (ids.contains(t)) edges.add(new GraphEdge(f.path(), t, "IMPORT"));
                    for (String t : phpTypes.resolveReferences(f)) {
                        if (!targets.contains(t) && ids.contains(t)) edges.add(new GraphEdge(f.path(), t, "REFERENCE"));
                    }
                }
                case "html" -> {
                    Set<String> targets = new LinkedHashSet<>();
                    for (ParsedFile.RawImport i : f.imports()) html.resolve(f.path(), i.name()).ifPresent(targets::add);
                    for (String t : targets) if (ids.contains(t)) edges.add(new GraphEdge(f.path(), t, "IMPORT"));
                }
                case "c", "cpp" -> {
                    Set<String> targets = new LinkedHashSet<>();
                    for (ParsedFile.RawImport i : f.imports()) cInclude.resolve(f.path(), i.name()).ifPresent(targets::add);
                    for (String t : targets) if (ids.contains(t)) edges.add(new GraphEdge(f.path(), t, "IMPORT"));
                }
                default -> { }
            }
        }
        return new Graph(nodes, edges);
    }

    private static String folderOf(String path) {
        int slash = path.lastIndexOf('/');
        return slash < 0 ? "" : path.substring(0, slash);
    }
}