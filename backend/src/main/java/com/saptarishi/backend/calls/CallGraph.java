package com.saptarishi.backend.calls;

import java.util.List;

public record CallGraph(List<MethodNode> methods, List<CallEdge> edges) {

    /** id = path#Type.name/arity. Overloads with the same arity share a node. */
    public record MethodNode(String id, String path, String type, String name, int arity, int line, int complexity) {}

    /** guess = every call site was matched by a unique method name only, not by a known receiver type. */
    public record CallEdge(String caller, String callee, int calls, boolean guess) {}
}