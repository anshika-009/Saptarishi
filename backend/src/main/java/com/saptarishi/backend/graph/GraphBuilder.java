package com.saptarishi.backend.graph;

import com.saptarishi.backend.parse.GoModSupport;
import com.saptarishi.backend.parse.ParsedFile;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Service;

@Service
public class GraphBuilder {

    /** Same fields the frontend already reads, plus loc and parsed. */
    public record GraphNode(String id, String folder, String language, int complexity,
                            int tokens, int loc, boolean parsed) {}
    /** type: IMPORT (explicit import) or REFERENCE (same-package use, no import needed). */
    public record GraphEdge(String source, String target, String type) {}
    public record Graph(List<GraphNode> nodes, List<GraphEdge> edges) {}

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