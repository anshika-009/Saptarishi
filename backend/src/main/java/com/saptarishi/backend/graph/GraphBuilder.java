package com.saptarishi.backend.graph;

import com.saptarishi.backend.parse.ParsedFile;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Service;

@Service
public class GraphBuilder {

    /** Same fields the frontend already reads, plus loc and parsed. */
    public record GraphNode(String id, String folder, String language, int complexity,
                            int tokens, int loc, boolean parsed) {}
    public record GraphEdge(String source, String target, String type) {}
    public record Graph(List<GraphNode> nodes, List<GraphEdge> edges) {}

    public Graph build(List<ParsedFile> files, boolean includeText) {
        List<ParsedFile> kept = files.stream()
                .filter(f -> includeText || !"text".equals(f.language()))
                .toList();

        Set<String> ids = new HashSet<>();
        List<GraphNode> nodes = new ArrayList<>(kept.size());
        for (ParsedFile f : kept) {
            ids.add(f.path());
            nodes.add(new GraphNode(f.path(), folderOf(f.path()), f.language(),
                    f.complexity(), f.tokens(), f.loc(), f.parsed()));
        }

        JavaImportResolver java = new JavaImportResolver(files);
        List<GraphEdge> edges = new ArrayList<>();
        for (ParsedFile f : kept) {
            if (!"java".equals(f.language()) || !f.parsed()) continue;
            for (String target : java.resolve(f)) {
                if (ids.contains(target)) edges.add(new GraphEdge(f.path(), target, "IMPORT"));
            }
        }
        return new Graph(nodes, edges);
    }

    private static String folderOf(String path) {
        int slash = path.lastIndexOf('/');
        return slash < 0 ? "" : path.substring(0, slash);
    }
}