package com.saptarishi.backend.calls;

import com.saptarishi.backend.calls.CallGraph.CallEdge;
import com.saptarishi.backend.calls.CallGraph.MethodNode;
import com.saptarishi.backend.parse.ParsedFile;
import com.saptarishi.backend.parse.ParsedFile.CallSite;
import com.saptarishi.backend.parse.ParsedFile.RawImport;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * Method-level call graph without a symbol solver. A call resolves exactly when its receiver type is known
 * and the method exists in the repo. Otherwise it falls back to a unique method name (flagged as a guess).
 * Calls into libraries or the standard library never become edges.
 *
 * <p>Java is resolved from its imports and packages ({@link Index#resolveJava}). Every other language is
 * resolved from the file-level graph instead: a call can only reach code in a file the caller can see,
 * which is the caller itself, the files it imports or includes (the edges the per-language import
 * resolvers already produced), and for a few languages the files that share its package or header
 * ({@link Index#visible}). That reuses each language's own import rules instead of repeating them here.
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

    /**
     * Extra names that are too common to guess from, per language: built-ins and the standard library's
     * most used methods. Java's list is unchanged, so its graph does not move.
     */
    private static final Map<String, Set<String>> EXTRA_COMMON = Map.of(
            "python", Set.of("print", "len", "range", "isinstance", "issubclass", "super", "str", "int", "float",
                    "bool", "list", "dict", "set", "tuple", "open", "enumerate", "zip", "map", "filter", "sorted",
                    "reversed", "min", "max", "sum", "any", "all", "getattr", "setattr", "hasattr", "delattr", "type",
                    "repr", "format", "iter", "abs", "round", "callable", "vars", "dir", "hash", "input", "append",
                    "extend", "insert", "pop", "update", "keys", "values", "items", "join", "split", "strip", "lower",
                    "upper", "replace", "startswith", "endswith", "encode", "decode", "copy", "sort", "reverse",
                    "count", "index", "find", "read", "write", "flush", "main", "setup", "teardown"),
            "ecma", Set.of("log", "error", "warn", "info", "debug", "push", "pop", "shift", "unshift", "slice",
                    "splice", "map", "filter", "reduce", "forEach", "find", "findIndex", "some", "every", "includes",
                    "indexOf", "join", "split", "trim", "replace", "concat", "keys", "values", "entries", "assign",
                    "stringify", "parse", "then", "catch", "finally", "resolve", "reject", "all", "require", "fetch",
                    "json", "text", "parseInt", "parseFloat", "isArray", "from", "call", "bind", "setTimeout",
                    "setInterval", "clearTimeout", "constructor", "render", "main"),
            "go", Set.of("println", "printf", "sprintf", "errorf", "fatal", "fatalf", "panic", "len", "cap", "make",
                    "new", "append", "copy", "delete", "close", "recover", "print", "Error", "String", "Close",
                    "Write", "Read", "Lock", "Unlock", "Done", "Add", "Wait", "Println", "Printf", "Sprintf",
                    "Errorf", "Fatal", "Fatalf", "Marshal", "Unmarshal", "New", "Get", "Set", "Len", "Less", "Swap",
                    "main", "init", "Run"),
            "csharp", Set.of("ToString", "Equals", "GetHashCode", "GetType", "Add", "Remove", "Contains", "Clear",
                    "Count", "Select", "Where", "ToList", "ToArray", "First", "FirstOrDefault", "Any", "All", "Length",
                    "Append", "Dispose", "Close", "WriteLine", "Write", "ReadLine", "Parse", "TryParse", "Format",
                    "Join", "Split", "Trim", "Replace", "StartsWith", "EndsWith", "Substring", "Get", "Set", "Main",
                    "Run", "Start", "Stop", "Execute", "Invoke", "Create", "Build", "Handle"),
            "rust", Set.of("clone", "unwrap", "expect", "unwrap_or", "unwrap_or_else", "unwrap_or_default", "map",
                    "map_err", "and_then", "ok_or", "iter", "into_iter", "collect", "push", "pop", "len", "is_empty",
                    "to_string", "to_owned", "as_str", "as_ref", "as_mut", "borrow", "borrow_mut", "lock", "new",
                    "default", "from", "into", "get", "insert", "remove", "contains", "cloned", "take", "drop", "fmt",
                    "eq", "cmp", "next", "main", "run", "build", "write", "read", "join", "parse", "send", "recv"),
            "c", Set.of("printf", "fprintf", "sprintf", "snprintf", "malloc", "calloc", "realloc", "free", "memcpy",
                    "memset", "memmove", "strlen", "strcpy", "strcmp", "strncpy", "strcat", "puts", "fopen", "fclose",
                    "fread", "fwrite", "exit", "abort", "assert", "sizeof", "main", "push_back", "emplace_back",
                    "size", "begin", "end", "find", "insert", "erase", "clear", "empty", "c_str", "swap", "move",
                    "make_shared", "make_unique", "get", "reset", "at", "front", "back", "data"),
            "ruby", Set.of("puts", "print", "p", "require", "require_relative", "raise", "include", "extend",
                    "attr_accessor", "attr_reader", "attr_writer", "private", "public", "protected", "new", "each",
                    "map", "select", "reject", "first", "last", "push", "pop", "shift", "length", "size", "to_s",
                    "to_i", "to_a", "to_h", "join", "split", "strip", "freeze", "dup", "format", "sprintf", "lambda",
                    "proc", "loop", "catch", "throw", "initialize", "call", "send", "tap", "then", "main"),
            "php", Set.of("count", "strlen", "isset", "empty", "array_map", "array_filter", "array_merge",
                    "array_keys", "array_values", "in_array", "implode", "explode", "sprintf", "printf", "print_r",
                    "var_dump", "json_encode", "json_decode", "trim", "str_replace", "strtolower", "strtoupper",
                    "substr", "strpos", "is_array", "is_null", "is_string", "intval", "floatval", "get", "set", "add",
                    "has", "all", "find", "create", "make", "handle", "run", "boot", "register", "render"));

    /** How many levels of #include are followed when working out what a C or C++ file can see. */
    private static final int HEADER_HOPS = 3;

    private record Resolved(MethodNode method, boolean guess) {}
    private record Key(String caller, String callee) {}

    /** Java only, or any language when the file-level graph is not at hand (tests): no import-based visibility. */
    public CallGraph build(List<ParsedFile> files) {
        return build(files, Map.of());
    }

    /**
     * @param dependencies for each file, the files it imports or references (the file-level graph's edges).
     *                     Used to decide which files a call can reach in every language but Java.
     */
    public CallGraph build(List<ParsedFile> files, Map<String, ? extends Collection<String>> dependencies) {
        List<ParsedFile> indexed = files.stream()
                .filter(f -> f.parsed() && ("java".equals(f.language()) || !f.methods().isEmpty()
                        || !f.calls().isEmpty()))
                .toList();
        Index index = new Index(indexed, dependencies);

        Map<Key, int[]> sites = new LinkedHashMap<>(); // {call sites, guessed call sites}
        for (ParsedFile f : indexed) {
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

    /** Languages that share names and files: a JavaScript file imports TypeScript and C includes C++ headers. */
    private static String family(String language) {
        return switch (language) {
            case "javascript", "typescript" -> "ecma";
            case "c", "cpp" -> "c";
            default -> language;
        };
    }

    private static String folderOf(String path) {
        int slash = path.lastIndexOf('/');
        return slash < 0 ? "" : path.substring(0, slash);
    }

    private static String fileName(String path) {
        return path.substring(path.lastIndexOf('/') + 1);
    }

    private static String stemOf(String path) {
        String n = fileName(path);
        int dot = n.lastIndexOf('.');
        return dot <= 0 ? n : n.substring(0, dot);
    }

    private static String lastFolder(String path) {
        String folder = folderOf(path);
        return folder.substring(folder.lastIndexOf('/') + 1);
    }

    /** Files that stand for their folder: {@code pkg/__init__.py}, {@code dir/index.js}, {@code util/mod.rs}. */
    private static final Set<String> FOLDER_FILES = Set.of("index", "__init__", "mod", "lib", "main");

    private static final class Index {
        final Map<String, MethodNode> methods = new LinkedHashMap<>();
        final Map<String, List<MethodNode>> byFileType = new HashMap<>();   // path|Type -> methods
        final Map<String, List<MethodNode>> byFile = new HashMap<>();       // path -> methods
        final Map<String, List<MethodNode>> byNameArity = new HashMap<>();  // family|name/arity -> methods
        final Map<String, List<MethodNode>> byType = new HashMap<>();       // family|Type -> methods
        final Map<String, String> typeToPath = new HashMap<>();             // Java: pkg.Type -> path
        final Map<String, Set<String>> typeFiles = new HashMap<>();         // family|Type -> files that declare it
        final Map<String, List<String>> moduleFiles = new HashMap<>();      // module name -> files
        final Map<String, List<String>> sameStem = new HashMap<>();         // C: family|stem -> files
        final Map<String, String> languageOf = new HashMap<>();
        private final Map<String, ? extends Collection<String>> dependencies;
        final Map<String, List<String>> filesByFolder = new HashMap<>();
        final Map<String, Set<String>> visibleCache = new HashMap<>();

        Index(List<ParsedFile> files, Map<String, ? extends Collection<String>> dependencies) {
            this.dependencies = dependencies;
            for (ParsedFile f : files) {
                languageOf.put(f.path(), f.language());
                String fam = family(f.language());
                for (var s : f.symbols()) {
                    if (!TYPE_KINDS.contains(s.kind())) continue;
                    if ("java".equals(f.language())) typeToPath.putIfAbsent(qualify(f.packageName(), s.name()), f.path());
                    typeFiles.computeIfAbsent(fam + "|" + s.name(), k -> new LinkedHashSet<>()).add(f.path());
                }
                for (var m : f.methods()) {
                    String id = id(f.path(), m.type(), m.name(), m.arity());
                    methods.merge(id, new MethodNode(id, f.path(), m.type(), m.name(), m.arity(), m.line(), m.complexity()),
                            (a, b) -> new MethodNode(a.id(), a.path(), a.type(), a.name(), a.arity(),
                                    Math.min(a.line(), b.line()), a.complexity() + b.complexity()));
                    if (!m.type().isEmpty()) {
                        typeFiles.computeIfAbsent(fam + "|" + m.type(), k -> new LinkedHashSet<>()).add(f.path());
                    }
                }
                filesByFolder.computeIfAbsent(folderOf(f.path()) + "|" + f.language(), k -> new ArrayList<>()).add(f.path());
                String stem = stemOf(f.path());
                if (FOLDER_FILES.contains(stem)) {
                    if (!lastFolder(f.path()).isEmpty()) moduleFiles.computeIfAbsent(lastFolder(f.path()), k -> new ArrayList<>()).add(f.path());
                } else {
                    moduleFiles.computeIfAbsent(stem, k -> new ArrayList<>()).add(f.path());
                    if (!lastFolder(f.path()).isEmpty()) moduleFiles.computeIfAbsent(lastFolder(f.path()), k -> new ArrayList<>()).add(f.path());
                }
                sameStem.computeIfAbsent(fam + "|" + stem, k -> new ArrayList<>()).add(f.path());
            }
            for (MethodNode m : methods.values()) {
                String fam = family(languageOf.get(m.path()));
                byFileType.computeIfAbsent(m.path() + "|" + m.type(), k -> new ArrayList<>()).add(m);
                byFile.computeIfAbsent(m.path(), k -> new ArrayList<>()).add(m);
                byNameArity.computeIfAbsent(fam + "|" + m.name() + "/" + m.arity(), k -> new ArrayList<>()).add(m);
                if (!m.type().isEmpty()) byType.computeIfAbsent(fam + "|" + m.type(), k -> new ArrayList<>()).add(m);
            }
        }

        private Collection<String> depsOf(String path) {
            Collection<String> d = dependencies.get(path);
            return d == null ? List.of() : d;
        }

        Resolved resolve(ParsedFile f, CallSite c) {
            return "java".equals(f.language()) ? resolveJava(f, c) : resolveGeneric(f, c);
        }

        // ---- Java: unchanged ----

        Resolved resolveJava(ParsedFile f, CallSite c) {
            String recv = c.receiver();
            if ("super".equals(recv)) return null;
            if (recv == null) return guess(f, c);

            String path = f.path();
            String type = c.callerType();
            if (!recv.isEmpty()) {
                path = resolveType(f, recv);
                if (path == null) return null; // JDK or library type: not our code
                type = recv;
            }
            MethodNode m = pick(byFileType.get(path + "|" + type), c.name(), c.arity());
            if (m == null) m = pick(byFile.get(path), c.name(), c.arity());
            return m != null ? new Resolved(m, false) : guess(f, c); // inherited methods land here
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

        // ---- every other language ----

        private Resolved resolveGeneric(ParsedFile f, CallSite c) {
            String recv = c.receiver();
            if ("super".equals(recv)) return null;
            if (recv == null) return guess(f, c);
            return recv.isEmpty() ? resolveUnqualified(f, c) : resolveQualified(f, c, recv);
        }

        /** {@code foo()}, {@code this.foo()}, {@code self.foo()}: the caller's own type, then free functions it can see. */
        private Resolved resolveUnqualified(ParsedFile f, CallSite c) {
            Set<String> vis = visible(f);
            MethodNode m = pick(byFileType.get(f.path() + "|" + c.callerType()), c.name(), c.arity());
            if (m == null && !c.callerType().isEmpty()) { // a partial class, or a C++ class defined across files
                m = pick(typeMethods(f, c.callerType(), vis), c.name(), c.arity());
            }
            if (m == null) m = pick(byFileType.get(f.path() + "|"), c.name(), c.arity()); // a free function in this file
            if (m == null) m = uniqueIn(vis, f.path(), c);
            return m != null ? new Resolved(m, false) : guess(f, c);
        }

        /** {@code Type.m()} or {@code module.m()}. */
        private Resolved resolveQualified(ParsedFile f, CallSite c, String recv) {
            String fam = family(f.language());
            Set<String> vis = visible(f);
            Set<String> declared = typeFiles.getOrDefault(fam + "|" + recv, Set.of());
            boolean seen = declared.stream().anyMatch(vis::contains);
            boolean guessed = false;
            List<MethodNode> pool = null;
            if (seen) {
                pool = typeMethods(f, recv, vis);
            } else if (declared.size() == 1) { // a type nobody imported, but there is exactly one: autoloaded code
                pool = byType.getOrDefault(fam + "|" + recv, List.of());
                guessed = true;
            }
            if (pool != null) {
                MethodNode m = pick(pool, c.name(), c.arity());
                return m != null ? new Resolved(m, guessed) : guess(f, c); // inherited or declared elsewhere
            }
            List<MethodNode> inModule = new ArrayList<>();
            for (String p : moduleFiles.getOrDefault(recv, List.of())) {
                if (vis.contains(p) && !p.equals(f.path())) inModule.addAll(byFile.getOrDefault(p, List.of()));
            }
            if (!inModule.isEmpty()) {
                MethodNode m = pick(inModule, c.name(), c.arity());
                return m != null ? new Resolved(m, false) : null;
            }
            // C++ namespaces look like types here (ns::f()), and nothing says which a name is: guess by name.
            return "cpp".equals(f.language()) ? guess(f, c) : null;
        }

        /** Methods of a type, from the files the caller can see. A type may be spread over several files. */
        private List<MethodNode> typeMethods(ParsedFile f, String type, Set<String> vis) {
            List<MethodNode> out = new ArrayList<>();
            for (MethodNode m : byType.getOrDefault(family(f.language()) + "|" + type, List.of())) {
                if (vis.contains(m.path())) out.add(m);
            }
            return out;
        }

        /**
         * A function in one of the files the caller can see: a free function, or a constructor, when exactly
         * one of them has this name. Two files that both define it leave the call unresolved.
         */
        private MethodNode uniqueIn(Set<String> vis, String own, CallSite c) {
            List<MethodNode> hits = new ArrayList<>();
            for (String p : vis) {
                if (p.equals(own)) continue;
                for (MethodNode m : byFile.getOrDefault(p, List.of())) {
                    if (m.name().equals(c.name()) && (m.type().isEmpty() || m.type().equals(m.name()))) hits.add(m);
                }
            }
            return pickUnique(hits, c.arity());
        }

        /** Same arity wins when it is one method; otherwise a name that appears once. */
        private static MethodNode pickUnique(List<MethodNode> hits, int arity) {
            List<MethodNode> same = hits.stream().filter(m -> m.arity() == arity).toList();
            if (same.size() == 1) return same.get(0);
            return hits.size() == 1 ? hits.get(0) : null;
        }

        /** What a call in this file can reach. Computed once per file. */
        private Set<String> visible(ParsedFile f) {
            return visibleCache.computeIfAbsent(f.path(), p -> {
                Set<String> v = new LinkedHashSet<>();
                v.add(p);
                String lang = f.language();
                String fam = family(lang);
                if (fam.equals("c")) { // includes of includes, and the source file behind each header
                    Set<String> frontier = Set.of(p);
                    for (int hop = 0; hop < HEADER_HOPS && !frontier.isEmpty(); hop++) {
                        Set<String> next = new LinkedHashSet<>();
                        for (String q : frontier) {
                            for (String d : depsOf(q)) if (v.add(d)) next.add(d);
                        }
                        frontier = next;
                    }
                    for (String q : new ArrayList<>(v)) {
                        for (String s : sameStem.getOrDefault(fam + "|" + stemOf(q), List.of())) v.add(s);
                    }
                } else {
                    v.addAll(depsOf(p));
                    if (lang.equals("go")) v.addAll(filesByFolder.getOrDefault(folderOf(p) + "|go", List.of())); // one package per folder
                }
                return v;
            });
        }

        private Resolved guess(ParsedFile f, CallSite c) {
            String lang = f.language();
            if (isCommon(lang, c.name())) return null;
            List<MethodNode> hits = byNameArity.get(family(lang) + "|" + c.name() + "/" + c.arity());
            return hits != null && hits.size() == 1 ? new Resolved(hits.get(0), true) : null;
        }

        private static boolean isCommon(String language, String name) {
            return COMMON.contains(name) || (!"java".equals(language)
                    && EXTRA_COMMON.getOrDefault(family(language), Set.of()).contains(name));
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
    }
}
