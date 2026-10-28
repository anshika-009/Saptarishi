package com.saptarishi.backend.parse;

import com.saptarishi.backend.parse.ParsedFile.CallSite;
import com.saptarishi.backend.parse.ParsedFile.MethodDecl;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import org.treesitter.TSNode;

/**
 * Call-graph facts for the tree-sitter languages other than Java (which keeps its own walk). A language
 * says what opens a named type, what is a named function or method, and how a call node reads; this class
 * does the one walk that ties them together:
 *
 * <ul>
 *   <li>{@link ParsedFile#methods()}: every named function or method, with the innermost type around it
 *       ({@code ""} for a free function), its parameter count, its line and its own complexity (1 + the
 *       decision points inside it, nested functions included, as for Java).
 *   <li>{@link ParsedFile#calls()}: every call made from inside one of those, attributed to the innermost
 *       named function. A callback or lambda is not a function here, so its calls belong to the function
 *       around it. Calls outside any function (module-level code, field initialisers) are skipped.
 * </ul>
 *
 * <p>The file's own complexity is not touched: it stays the default (functions + decisions over the whole
 * file), so health scores do not move when a language gains a call graph.
 *
 * <p>Receiver convention, shared with Java ({@link CallSite}): {@code ""} = this, self, or an unqualified
 * call; {@code null} = unknown; otherwise a simple name that is either a type or, for languages with
 * modules and packages, the module a call goes through ({@code utils.parse()} gives {@code "utils"}).
 * Which of the two it is, is decided later by {@code CallGraphBuilder}, which can see the other files.
 *
 * <p>The language beans are shared by all parsing threads, so nothing here or in a subclass may keep
 * per-file state in a field: it lives in the {@link FileCtx} that {@link #beginFile} returns.
 */
abstract class TreeSitterCallSupport extends TreeSitterSupport {

    /** Innermost-first chain of the named types around a point. */
    protected record TypeCtx(String name, TSNode node, TypeCtx parent) {}

    /** What the simple names inside one function stand for: parameters and locals, then fields. */
    protected record Scope(Map<String, String> locals, Map<String, String> fields) {
        static final Scope EMPTY = new Scope(Map.of(), Map.of());

        /** The declared type of a name, or null. Locals shadow fields. */
        String typeOf(String name) {
            String t = locals.get(name);
            return t != null ? t : fields.get(name);
        }
    }

    /** One named function or method that is being read. */
    protected static final class Callable {
        final TSNode node;
        final String type;
        final String name;
        final int arity;
        final int line;
        final TypeCtx types;
        int decisions;
        Scope scope;

        protected Callable(TSNode node, String type, String name, int arity, int line, TypeCtx types) {
            this.node = node;
            this.type = type;
            this.name = name;
            this.arity = arity;
            this.line = line;
            this.types = types;
        }
    }

    /** Per-file facts a language needs (import aliases, fields of types). Subclass it to add more. */
    protected static class FileCtx {
        /** Local name of an import to what it stands for: a type or the last name of a module. */
        public final Map<String, String> aliases = new HashMap<>();
        /** Fields of a type, keyed by the start byte of the type's node, so each type is scanned once. */
        public final Map<Integer, Map<String, String>> fieldsByType = new HashMap<>();
    }

    @Override
    public boolean extractsCalls() { return true; }

    // ---- what a language supplies ----

    /** A node that opens a named type (class, struct, trait, impl block...). */
    protected abstract boolean opensType(String nodeType);

    /** The name of the type this node declares, or null to ignore it (an anonymous class). */
    protected abstract String typeName(TSNode node, Source src);

    /**
     * Whether this node is a named function or method. Return null when it is not. {@code nested} is true
     * when it sits inside another function, which most languages treat as a local helper with no owner type.
     */
    protected abstract Callable openCallable(TSNode node, String nodeType, TypeCtx types, boolean nested,
                                             FileCtx file, Source src);

    /** Add a call site to {@code into} if this node is a call. Called for every node inside a function. */
    protected abstract void callsOf(TSNode node, String nodeType, Callable caller, FileCtx file, Source src,
                                    List<CallSite> into);

    /** Names of the parameters, locals and fields of one function. Computed once per function, on demand. */
    protected Scope scopeOf(Callable c, FileCtx file, Source src) { return Scope.EMPTY; }

    /** Called once before the walk, for facts that need the whole file (imports, to resolve aliases). */
    protected FileCtx beginFile(TSNode root, Source src) { return new FileCtx(); }

    /** The scope of {@code c}, built the first time it is asked for. */
    protected final Scope scope(Callable c, FileCtx file, Source src) {
        if (c.scope == null) c.scope = scopeOf(c, file, src);
        return c.scope;
    }

    // ---- the walk ----

    private static final int ENTER = 0;
    private static final int EXIT_TYPE = 1;
    private static final int EXIT_CALLABLE = 2;

    private record Frame(TSNode node, int kind) {}

    @Override
    protected void finish(TSNode root, Source src, Collected out) {
        FileCtx file = beginFile(root, src);
        List<Callable> declared = new ArrayList<>();
        List<Callable> active = new ArrayList<>();
        List<CallSite> calls = new ArrayList<>();
        TypeCtx types = null;

        Deque<Frame> stack = new ArrayDeque<>();
        stack.push(new Frame(root, ENTER));
        while (!stack.isEmpty()) {
            Frame f = stack.pop();
            if (f.kind() == EXIT_TYPE) { types = types.parent(); continue; }
            if (f.kind() == EXIT_CALLABLE) { active.remove(active.size() - 1); continue; }

            TSNode node = f.node();
            String type = node.getType();
            if (isComment(type)) continue;

            if (!active.isEmpty() && isDecision(node, type, src)) {
                for (Callable c : active) c.decisions++; // a nested function's decisions belong to the outer one too
            }

            int exit = ENTER;
            if (opensType(type)) {
                String name = typeName(node, src);
                if (name != null) {
                    types = new TypeCtx(name, node, types);
                    exit = EXIT_TYPE;
                }
            } else {
                // nested = the nearest scope around this node is a function (not a class declared inside one)
                boolean nested = !active.isEmpty() && (types == null
                        || types.node().getStartByte() < active.get(active.size() - 1).node.getStartByte());
                Callable c = openCallable(node, type, types, nested, file, src);
                if (c != null) {
                    declared.add(c);
                    active.add(c);
                    exit = EXIT_CALLABLE;
                }
            }
            if (exit == ENTER && !active.isEmpty()) {
                callsOf(node, type, active.get(active.size() - 1), file, src, calls);
            }
            if (exit != ENTER) stack.push(new Frame(node, exit));
            if (isAtomic(type)) continue;
            for (int i = node.getChildCount() - 1; i >= 0; i--) stack.push(new Frame(node.getChild(i), ENTER));
        }

        for (Callable c : declared) {
            out.methods.add(new MethodDecl(c.type, c.name, c.arity, c.line, 1 + c.decisions));
        }
        out.calls.addAll(calls);
    }

    // ---- helpers shared by the languages ----

    /** Every node of the tree, parents before children, without recursion. */
    protected static void walk(TSNode root, Consumer<TSNode> visitor) {
        Deque<TSNode> stack = new ArrayDeque<>();
        stack.push(root);
        while (!stack.isEmpty()) {
            TSNode n = stack.pop();
            visitor.accept(n);
            for (int i = n.getChildCount() - 1; i >= 0; i--) stack.push(n.getChild(i));
        }
    }

    /** The first direct child whose type is one of these, or null. */
    protected static TSNode childOf(TSNode node, Set<String> types) {
        for (int i = 0; i < node.getChildCount(); i++) {
            if (types.contains(node.getChild(i).getType())) return node.getChild(i);
        }
        return null;
    }

    /** The last direct child with this type, or null. */
    protected static TSNode lastChild(TSNode node, String type) {
        for (int i = node.getChildCount() - 1; i >= 0; i--) {
            if (type.equals(node.getChild(i).getType())) return node.getChild(i);
        }
        return null;
    }

    /** The first direct child that comes after a child of this type (the node after an {@code =}, say). */
    protected static TSNode after(TSNode node, String type) {
        for (int i = 0; i + 1 < node.getChildCount(); i++) {
            if (type.equals(node.getChild(i).getType())) return node.getChild(i + 1);
        }
        return null;
    }

    /** Direct children that are not one of these types (punctuation and comments, usually). */
    protected static int countExcept(TSNode node, Set<String> skip) {
        int n = 0;
        for (int i = 0; i < node.getChildCount(); i++) {
            if (!skip.contains(node.getChild(i).getType())) n++;
        }
        return n;
    }

    protected static boolean isUpper(String name) {
        return name != null && !name.isEmpty() && Character.isUpperCase(name.charAt(0));
    }

    /** The part of a dotted, {@code ::} or backslash separated name after the last separator. */
    protected static String lastSegment(String name) {
        int cut = -1;
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (c == '.' || c == ':' || c == '\\' || c == '/') cut = i;
        }
        return cut < 0 ? name : name.substring(cut + 1);
    }

    protected static void putIfKnown(Map<String, String> m, String name, String type) {
        if (name != null && !name.isEmpty() && type != null && !type.isEmpty()) m.putIfAbsent(name, type);
    }
}
