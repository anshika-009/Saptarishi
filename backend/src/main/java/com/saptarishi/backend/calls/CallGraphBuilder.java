package com.saptarishi.backend.calls;

import com.saptarishi.backend.calls.CallGraph.CallEdge;
import com.saptarishi.backend.calls.CallGraph.MethodNode;
import com.saptarishi.backend.parse.ParsedFile;
import com.saptarishi.backend.parse.ParsedFile.CallSite;
import com.saptarishi.backend.parse.ParsedFile.RawImport;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * Method-level call graph for Java without a symbol solver. A call resolves exactly when its receiver
 * type is known and the method exists in the repo. Otherwise it falls back to a unique method name
 * (flagged as a guess). Calls into libraries or the JDK never become edges.
 */
@Component
public class CallGraphBuilder {

    private static final Set<String> TYPE_KINDS = Set.of("CLASS", "INTERFACE", "ENUM", "RECORD");
    /** Names too common to guess from: they almost always mean a JDK or library method. */
    private static final Set<String> COMMON = Set.of(
            "get", "set", "add", "put", "remove", "contains", "size", "isEmpty", "equals", "hashCode",
            "toString", "close", "run", "apply", "accept", "test", "build", "of", "create", "valueOf",
            "name", "value", "next", "clear", "init", "start", "stop", "iterator", "length",
            "getName", "getId", "setName", "setId");

    private record Resolved(MethodNode method, boolean guess) {}
    private record Key(String caller, String callee) {}

    public CallGraph build(List<ParsedFile> files) {
        List<ParsedFile> java = files.stream()
                .filter(f -> "java".equals(f.language()) && f.parsed()).toList();
        Index index = new Index(java);

        Map<Key, int[]> sites = new LinkedHashMap<>(); // {call sites, guessed call sites}
        for (ParsedFile f : java) {
            for (CallSite c : f.calls()) {
                String callerId = id(f.path(), c.callerType(), c.caller(), c.callerArity());
                if (!index.methods.containsKey(callerId)) continue;
                Resolved r = index.resolve(f, c);
                if (r == null || r.method().id().equals(callerId)) continue;
                int[] n = sites.computeIfAbsent(new Key(callerId, r.method().id()), k -> new int[2]);
                n[0]++;
                if (r.guess()) n[1]++;
            }
        }
        List<CallEdge> edges = sites.entrySet().stream()
                .map(e -> new CallEdge(e.getKey().caller(), e.getKey().callee(),
                        e.getValue()[0], e.getValue()[1] == e.getValue()[0]))
                .toList();
        return new CallGraph(List.copyOf(index.methods.values()), edges);
    }

    private static String id(String path, String type, String name, int arity) {
        return path + "#" + type + "." + name + "/" + arity;
    }

    private static String qualify(String pkg, String simple) {
        return pkg.isEmpty() ? simple : pkg + "." + simple;
    }

    private static String stripLast(String s) {
        int dot = s.lastIndexOf('.');
        return dot < 0 ? null : s.substring(0, dot);
    }

    private static final class Index {
        final Map<String, MethodNode> methods = new LinkedHashMap<>();
        final Map<String, List<MethodNode>> byFileType = new HashMap<>();   // path|Type -> methods
        final Map<String, List<MethodNode>> byFile = new HashMap<>();       // path -> methods
        final Map<String, List<MethodNode>> byNameArity = new HashMap<>();  // name/arity -> methods
        final Map<String, String> typeToPath = new HashMap<>();             // pkg.Type -> path

        Index(List<ParsedFile> java) {
            for (ParsedFile f : java) {
                for (var s : f.symbols()) {
                    if (TYPE_KINDS.contains(s.kind())) typeToPath.putIfAbsent(qualify(f.packageName(), s.name()), f.path());
                }
                for (var m : f.methods()) {
                    String id = id(f.path(), m.type(), m.name(), m.arity());
                    methods.merge(id, new MethodNode(id, f.path(), m.type(), m.name(), m.arity(), m.line(), m.complexity()),
                            (a, b) -> new MethodNode(a.id(), a.path(), a.type(), a.name(), a.arity(),
                                    Math.min(a.line(), b.line()), a.complexity() + b.complexity()));
                }
            }
            for (MethodNode m : methods.values()) {
                byFileType.computeIfAbsent(m.path() + "|" + m.type(), k -> new ArrayList<>()).add(m);
                byFile.computeIfAbsent(m.path(), k -> new ArrayList<>()).add(m);
                byNameArity.computeIfAbsent(m.name() + "/" + m.arity(), k -> new ArrayList<>()).add(m);
            }
        }

        Resolved resolve(ParsedFile f, CallSite c) {
            String recv = c.receiver();
            if ("super".equals(recv)) return null;
            if (recv == null) return guess(c);

            String path = f.path();
            String type = c.callerType();
            if (!recv.isEmpty()) {
                path = resolveType(f, recv);
                if (path == null) return null; // JDK or library type: not our code
                type = recv;
            }
            MethodNode m = pick(byFileType.get(path + "|" + type), c.name(), c.arity());
            if (m == null) m = pick(byFile.get(path), c.name(), c.arity());
            return m != null ? new Resolved(m, false) : guess(c); // inherited methods land here
        }

        private Resolved guess(CallSite c) {
            if (COMMON.contains(c.name())) return null;
            List<MethodNode> hits = byNameArity.get(c.name() + "/" + c.arity());
            return hits != null && hits.size() == 1 ? new Resolved(hits.get(0), true) : null;
        }

        /** Same arity wins; otherwise a name that appears once (covers varargs). */
        private static MethodNode pick(List<MethodNode> candidates, String name, int arity) {
            if (candidates == null) return null;
            MethodNode byName = null;
            int nameHits = 0;
            for (MethodNode m : candidates) {
                if (!m.name().equals(name)) continue;
                if (m.arity() == arity) return m;
                byName = m;
                nameHits++;
            }
            return nameHits == 1 ? byName : null;
        }

        /** Which file declares the simple type name, as seen from f: own file, explicit import, same package, wildcard import. */
        private String resolveType(ParsedFile f, String simple) {
            for (var s : f.symbols()) {
                if (TYPE_KINDS.contains(s.kind()) && s.name().equals(simple)) return f.path();
            }
            for (RawImport imp : f.imports()) {
                if (imp.isStatic() || imp.isWildcard() || !imp.name().endsWith("." + simple)) continue;
                for (String q = imp.name(); q != null; q = stripLast(q)) { // a.Outer.Inner -> a.Outer
                    String p = typeToPath.get(q);
                    if (p != null) return p;
                }
                return null; // explicit import of a library type
            }
            String p = typeToPath.get(qualify(f.packageName(), simple));
            if (p != null) return p;
            for (RawImport imp : f.imports()) {
                if (!imp.isWildcard() || imp.isStatic()) continue;
                p = typeToPath.get(imp.name() + "." + simple);
                if (p != null) return p;
            }
            return null;
        }
    }
}