package com.saptarishi.backend.calls;

import com.saptarishi.backend.calls.CallGraph.CallEdge;
import com.saptarishi.backend.graph.GraphBuilder;
import com.saptarishi.backend.parse.ParsedFile;
import java.util.List;
import java.util.Optional;

/** Shared by the per-language call graph tests: build a call graph the way GraphService does. */
final class CallGraphAssert {

    private CallGraphAssert() {}

    /** The file-level graph first (its edges say which files can see which), then the call graph. */
    static CallGraph build(List<ParsedFile> files) {
        GraphBuilder.Graph graph = new GraphBuilder().build(files, false);
        return new CallGraphBuilder().build(files, graph.dependencies());
    }

    /** path#Type.name/arity, with an empty type for a free function. */
    static String id(String path, String type, String name, int arity) {
        return path + "#" + type + "." + name + "/" + arity;
    }

    static Optional<CallEdge> edge(CallGraph g, String from, String to) {
        return g.edges().stream().filter(e -> e.caller().equals(from) && e.callee().equals(to)).findFirst();
    }

    static boolean hasMethod(CallGraph g, String id) {
        return g.methods().stream().anyMatch(m -> m.id().equals(id));
    }
}
