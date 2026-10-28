package com.saptarishi.backend.parse;

import com.saptarishi.backend.parse.ParsedFile.CallSite;
import com.saptarishi.backend.parse.ParsedFile.MethodDecl;
import com.saptarishi.backend.parse.ParsedFile.RawImport;
import com.saptarishi.backend.parse.ParsedFile.Symbol;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.springframework.stereotype.Component;
import org.treesitter.TSLanguage;
import org.treesitter.TSNode;
import org.treesitter.TreeSitterJava;

/**
 * Java, read with the tree-sitter grammar. Facts and definitions are the ones the JavaParser version
 * produced, so scores and graphs do not move:
 *
 * <ul>
 *   <li>Imports: {@code a.b.C}, with {@code isStatic} and {@code isWildcard} ({@code a.b.*} is {@code a.b}).
 *   <li>Symbols: classes, interfaces, enums, records (annotation types count as CLASS), methods and
 *       constructors, on the line the declaration starts (annotations included).
 *   <li>Complexity: per method or constructor, 1 + {@code if}, loops, {@code catch}, {@code ?:}, each
 *       {@code case} label ({@code default} is free), {@code &&} and {@code ||}, counting everything inside
 *       it, nested lambdas and anonymous classes too. The file's complexity is the sum, so code outside any
 *       method (static blocks, field initialisers) adds nothing.
 *   <li>Call graph facts: every method, and every call or {@code new} made from inside one, with the simple
 *       type name of the receiver where it can be told cheaply (see {@link #typeOf}).
 * </ul>
 *
 * <p>A file with a syntax error is still reported as unparsed, as before, although tree-sitter could read
 * it: see {@link #rejectsSyntaxErrors()}.
 */
@Component
public class JavaLanguageSupport extends TreeSitterSupport {

    private static final Set<String> FUNCTIONS = Set.of("method_declaration", "constructor_declaration");
    private static final Set<String> DECISIONS = Set.of(
            "if_statement", "for_statement", "enhanced_for_statement", "while_statement", "do_statement",
            "catch_clause", "ternary_expression", "&&", "||");
    private static final Set<String> TYPES = Set.of(
            "class_declaration", "interface_declaration", "enum_declaration", "record_declaration",
            "annotation_type_declaration");
    /** Parents whose direct identifier children are names being declared or qualified, not names used. */
    private static final Set<String> NAMING_PARENTS = Set.of(
            "package_declaration", "import_declaration", "scoped_identifier", "scoped_type_identifier",
            "class_declaration", "interface_declaration", "enum_declaration", "record_declaration",
            "annotation_type_declaration", "method_declaration", "constructor_declaration",
            "compact_constructor_declaration", "enum_constant", "annotation_type_element_declaration",
            "formal_parameter", "catch_formal_parameter", "labeled_statement");
    private static final Set<String> NOT_ARGUMENTS = Set.of("(", ")", ",", "line_comment", "block_comment");

    @Override
    public String language() { return "java"; }

    @Override
    public boolean extractsCalls() { return true; }

    @Override
    protected TSLanguage grammar() { return new TreeSitterJava(); }

    @Override
    protected Set<String> functionTypes() { return FUNCTIONS; }

    @Override
    protected Set<String> decisionTypes() { return DECISIONS; }

    @Override
    protected boolean isComment(String type) {
        return type.equals("line_comment") || type.equals("block_comment");
    }

    @Override
    protected boolean isAtomic(String type) { return type.equals("string_literal"); }

    @Override
    protected boolean rejectsSyntaxErrors() { return true; }

    /** A {@code case} label is a decision, a {@code default} is not. */
    @Override
    protected boolean isDecision(TSNode node, String type, Source src) {
        if (type.equals("switch_label")) {
            return node.getChildCount() > 0 && "case".equals(node.getChild(0).getType());
        }
        return super.isDecision(node, type, src);
    }

    // ---- package, imports, symbols: need nothing but the node itself ----

    @Override
    protected void visit(TSNode node, String type, Source src, Collected out) {
        switch (type) {
            case "package_declaration" -> {
                TSNode name = nameNode(node);
                if (name != null && out.packageName.isEmpty()) out.packageName = qualified(name, src);
            }
            case "import_declaration" -> {
                TSNode name = nameNode(node);
                if (name == null) return;
                boolean isStatic = child(node, "static") != null;
                boolean wildcard = child(node, "asterisk") != null;
                out.imports.add(new RawImport(qualified(name, src), isStatic, wildcard));
            }
            case "class_declaration", "annotation_type_declaration" -> addSymbol(node, "CLASS", src, out);
            case "interface_declaration" -> addSymbol(node, "INTERFACE", src, out);
            case "enum_declaration" -> addSymbol(node, "ENUM", src, out);
            case "record_declaration" -> addSymbol(node, "RECORD", src, out);
            case "method_declaration" -> addSymbol(node, "METHOD", src, out);
            case "constructor_declaration" -> addSymbol(node, "CONSTRUCTOR", src, out);
            default -> { }
        }
    }

    private static void addSymbol(TSNode node, String kind, Source src, Collected out) {
        TSNode name = child(node, "identifier");
        if (name != null) out.symbols.add(new Symbol(src.text(name), kind, src.line(node)));
    }

    private static TSNode nameNode(TSNode node) {
        TSNode n = child(node, "scoped_identifier");
        return n != null ? n : child(node, "identifier");
    }

    /** The dotted name without any whitespace or comment that sits between its parts. */
    private static String qualified(TSNode name, Source src) {
        if (name.getChildCount() == 0) return src.text(name);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < name.getChildCount(); i++) {
            TSNode c = name.getChild(i);
            String t = c.getType();
            if (t.equals("scoped_identifier")) sb.append(qualified(c, src));
            else if (t.equals("identifier") || t.equals(".")) sb.append(src.text(c));
        }
        return sb.toString();
    }

    // ---- everything that needs to know the method, the type or the parent a node sits in ----

    /** Innermost-first chain of the named types around a point. Anonymous classes are not in it. */
    private record TypeCtx(String name, TSNode node, TypeCtx parent) {}

    private record Scope(Map<String, String> locals, Map<String, String> fields) {}

    private static final class Callable {
        final TSNode node;
        final String type;
        final String name;
        final int arity;
        final int line;
        final TypeCtx types;
        int decisions;
        Scope scope;

        Callable(TSNode node, String type, String name, int arity, int line, TypeCtx types) {
            this.node = node;
            this.type = type;
            this.name = name;
            this.arity = arity;
            this.line = line;
            this.types = types;
        }
    }

    private record Frame(TSNode node, TSNode parent, int index, boolean exit) {}

    /**
     * One walk, with an explicit stack because a long {@code a + b + c ...} is thousands of nodes deep.
     * It keeps the current type chain and the methods being read, so nothing asks a node for its parent.
     */
    @Override
    protected void finish(TSNode root, Source src, Collected out) {
        List<Callable> declared = new ArrayList<>();
        List<Callable> active = new ArrayList<>();
        List<CallSite> calls = new ArrayList<>();
        List<CallSite> creations = new ArrayList<>();
        Set<String> refs = new TreeSet<>();
        TypeCtx types = null;

        Deque<Frame> stack = new ArrayDeque<>();
        stack.push(new Frame(root, null, -1, false));
        while (!stack.isEmpty()) {
            Frame f = stack.pop();
            TSNode node = f.node();
            String type = node.getType();
            if (f.exit()) {
                if (TYPES.contains(type)) types = types.parent();
                else active.remove(active.size() - 1);
                continue;
            }
            if (isComment(type)) continue;

            if (isDecision(node, type, src)) {
                for (Callable c : active) c.decisions++; // a nested method's decisions belong to the outer one too
            }
            collectRef(node, type, f.parent(), f.index(), src, refs);

            boolean opens = false;
            if (TYPES.contains(type)) {
                TSNode name = child(node, "identifier");
                types = new TypeCtx(name == null ? "" : src.text(name), node, types);
                opens = true;
            } else if (FUNCTIONS.contains(type)) {
                TSNode name = child(node, "identifier");
                Callable c = new Callable(node, types == null ? "" : types.name(),
                        name == null ? "" : src.text(name), parameterCount(node), src.line(node), types);
                declared.add(c);
                active.add(c);
                opens = true;
            } else if (type.equals("method_invocation") && !active.isEmpty()) {
                CallSite call = methodCall(node, active.get(active.size() - 1), src);
                if (call != null) calls.add(call);
            } else if (type.equals("object_creation_expression") && !active.isEmpty()) {
                CallSite call = creation(node, active.get(active.size() - 1), src);
                if (call != null) creations.add(call);
            }
            if (opens) stack.push(new Frame(node, null, -1, true));
            if (isAtomic(type)) continue;
            for (int i = node.getChildCount() - 1; i >= 0; i--) {
                stack.push(new Frame(node.getChild(i), node, i, false));
            }
        }

        int complexity = 0;
        for (Callable c : declared) {
            out.methods.add(new MethodDecl(c.type, c.name, c.arity, c.line, 1 + c.decisions));
            complexity += 1 + c.decisions;
        }
        out.complexity = complexity;
        out.calls.addAll(calls);
        out.calls.addAll(creations);
        out.typeRefs.addAll(refs);
    }

    /**
     * Simple names of types this file uses: declared types (fields, params, extends...), annotations, and
     * capitalised names in expressions (Owner.create(), Color.RED). Over-collecting is harmless: a name only
     * becomes an edge if a class with that name exists in the same package.
     */
    private static void collectRef(TSNode node, String type, TSNode parent, int index, Source src, Set<String> refs) {
        switch (type) {
            case "type_identifier" -> addRef(refs, src.text(node));
            case "identifier" -> {
                if (parent != null && !isNameSlot(parent, index)) addRef(refs, src.text(node));
            }
            case "scoped_identifier" -> {
                // @a.b.Foo: only the last part is the annotation's name
                if (parent != null && isAnnotation(parent.getType())) {
                    TSNode last = lastChild(node, "identifier");
                    if (last != null) addRef(refs, src.text(last));
                }
            }
            default -> { }
        }
    }

    private static boolean isNameSlot(TSNode parent, int index) {
        String p = parent.getType();
        if (NAMING_PARENTS.contains(p)) return true;
        return switch (p) {
            case "variable_declarator" -> index == 0;
            case "method_invocation" -> index == parent.getChildCount() - 2;
            case "field_access" -> index == parent.getChildCount() - 1;
            default -> false;
        };
    }

    private static boolean isAnnotation(String type) {
        return type.equals("annotation") || type.equals("marker_annotation");
    }

    private static void addRef(Set<String> refs, String name) {
        if (!name.isEmpty() && Character.isUpperCase(name.charAt(0))) refs.add(name);
    }

    // ---- calls ----

    private static CallSite methodCall(TSNode call, Callable caller, Source src) {
        int n = call.getChildCount();
        TSNode args = child(call, "argument_list");
        if (n < 2 || args == null) return null;
        TSNode name = call.getChild(n - 2);
        if (!name.getType().equals("identifier")) return null;

        String receiver = "";
        if (n >= 3 && ".".equals(call.getChild(1).getType())) {
            if ("super".equals(call.getChild(2).getType())) return null; // Outer.super.f()
            if (caller.scope == null) caller.scope = scopeOf(caller, src);
            receiver = typeOf(call.getChild(0), caller.scope, src);
        }
        if ("super".equals(receiver)) return null;
        return new CallSite(caller.type, caller.name, caller.arity, src.text(name), argumentCount(args), receiver);
    }

    private static CallSite creation(TSNode created, Callable caller, Source src) {
        TSNode typeNode = creationType(created);
        String type = simpleTypeName(typeNode, src);
        TSNode args = child(created, "argument_list");
        if (type == null || args == null) return null;
        return new CallSite(caller.type, caller.name, caller.arity, type, argumentCount(args), type);
    }

    /** The type after {@code new}, skipping explicit constructor type arguments. */
    private static TSNode creationType(TSNode created) {
        boolean afterNew = false;
        for (int i = 0; i < created.getChildCount(); i++) {
            TSNode c = created.getChild(i);
            String t = c.getType();
            if (!afterNew) {
                afterNew = t.equals("new");
            } else if (!t.equals("type_arguments") && !isAnnotation(t)) {
                return c;
            }
        }
        return null;
    }

    private static int argumentCount(TSNode argumentList) {
        int n = 0;
        for (int i = 0; i < argumentList.getChildCount(); i++) {
            if (!NOT_ARGUMENTS.contains(argumentList.getChild(i).getType())) n++;
        }
        return n;
    }

    private static int parameterCount(TSNode callable) {
        TSNode params = child(callable, "formal_parameters");
        if (params == null) return 0;
        int n = 0;
        for (int i = 0; i < params.getChildCount(); i++) {
            String t = params.getChild(i).getType();
            if (t.equals("formal_parameter") || t.equals("spread_parameter")) n++;
        }
        return n;
    }

    /**
     * Simple type name of the expression a method is called on: "" for this, "super", the declared type of
     * a local or field, the class in a static call, the type in {@code new T()} or a cast. Null when it can't
     * be told cheaply (a chained call, an array access, ...).
     */
    private static String typeOf(TSNode e, Scope s, Source src) {
        switch (e.getType()) {
            case "this" -> { return ""; }
            case "super" -> { return "super"; }
            case "parenthesized_expression" -> {
                return e.getChildCount() >= 3 ? typeOf(e.getChild(1), s, src) : null;
            }
            case "cast_expression" -> {
                return e.getChildCount() >= 2 ? simpleTypeName(e.getChild(1), src) : null;
            }
            case "object_creation_expression" -> { return simpleTypeName(creationType(e), src); }
            case "identifier" -> {
                String id = src.text(e);
                String t = s.locals().get(id);
                if (t == null) t = s.fields().get(id);
                if (t != null) return t;
                return !id.isEmpty() && Character.isUpperCase(id.charAt(0)) ? id : null; // Util.trim(): static call
            }
            case "field_access" -> {
                int n = e.getChildCount();
                if (n < 3) return null;
                TSNode field = e.getChild(n - 1);
                if (field.getType().equals("this")) return ""; // Outer.this
                String id = src.text(field);
                if (isThis(e.getChild(0))) return s.fields().get(id);
                return !id.isEmpty() && Character.isUpperCase(id.charAt(0)) ? id : null; // pkg.Util.trim()
            }
            default -> { return null; }
        }
    }

    private static boolean isThis(TSNode e) {
        if (e.getType().equals("this")) return true;
        return e.getType().equals("field_access") && e.getChildCount() > 0
                && e.getChild(e.getChildCount() - 1).getType().equals("this"); // Outer.this
    }

    /** The last name of a type: {@code List} for {@code List<String>}, {@code Inner} for {@code a.Outer.Inner}. */
    private static String simpleTypeName(TSNode t, Source src) {
        if (t == null) return null;
        switch (t.getType()) {
            case "type_identifier" -> { return src.text(t); }
            case "generic_type" -> { return t.getChildCount() > 0 ? simpleTypeName(t.getChild(0), src) : null; }
            case "scoped_type_identifier" -> {
                TSNode last = lastChild(t, "type_identifier");
                return last == null ? null : src.text(last);
            }
            default -> { return null; } // primitives, arrays, ...
        }
    }

    // ---- what the names in one method stand for ----

    /** Parameters, then locals (including those of nested lambdas and classes), then fields of the types around. */
    private static Scope scopeOf(Callable c, Source src) {
        Map<String, String> locals = new HashMap<>();
        TSNode params = child(c.node, "formal_parameters");
        if (params != null) addParameters(params, locals, src);

        Deque<TSNode> stack = new ArrayDeque<>();
        stack.push(c.node);
        while (!stack.isEmpty()) {
            TSNode n = stack.pop();
            switch (n.getType()) {
                case "local_variable_declaration", "field_declaration", "constant_declaration" ->
                        addDeclarators(n, locals, src);
                case "enhanced_for_statement" -> addNamed(n, locals, src, false);
                case "resource" -> addNamed(n, locals, src, true);
                default -> { }
            }
            for (int i = n.getChildCount() - 1; i >= 0; i--) stack.push(n.getChild(i));
        }

        Map<String, String> fields = new HashMap<>();
        for (TypeCtx t = c.types; t != null; t = t.parent()) {
            for (String body : List.of("class_body", "interface_body", "enum_body", "annotation_type_body")) {
                TSNode b = child(t.node(), body);
                if (b != null) addMembers(b, fields, src);
            }
            if (t.node().getType().equals("record_declaration")) {
                TSNode rp = child(t.node(), "formal_parameters");
                if (rp != null) addParameters(rp, fields, src);
            }
        }
        return new Scope(locals, fields);
    }

    private static void addMembers(TSNode body, Map<String, String> fields, Source src) {
        for (int i = 0; i < body.getChildCount(); i++) {
            TSNode m = body.getChild(i);
            switch (m.getType()) {
                case "field_declaration", "constant_declaration" -> addDeclarators(m, fields, src);
                case "enum_body_declarations" -> addMembers(m, fields, src);
                default -> { }
            }
        }
    }

    private static void addParameters(TSNode params, Map<String, String> into, Source src) {
        for (int i = 0; i < params.getChildCount(); i++) {
            TSNode p = params.getChild(i);
            if (p.getType().equals("formal_parameter")) {
                TSNode name = child(p, "identifier");
                if (name != null && child(p, "dimensions") == null) {
                    put(into, src.text(name), simpleTypeName(firstNonModifier(p), src));
                }
            } else if (p.getType().equals("spread_parameter")) {
                TSNode vd = child(p, "variable_declarator");
                TSNode name = vd == null ? null : child(vd, "identifier");
                if (name != null) put(into, src.text(name), simpleTypeName(firstNonModifier(p), src));
            }
        }
    }

    /** {@code T a = ..., b = ...;} in a method or a class body. */
    private static void addDeclarators(TSNode decl, Map<String, String> into, Source src) {
        TSNode typeNode = firstNonModifier(decl);
        for (int i = 0; i < decl.getChildCount(); i++) {
            TSNode vd = decl.getChild(i);
            if (!vd.getType().equals("variable_declarator")) continue;
            TSNode name = child(vd, "identifier");
            if (name == null || child(vd, "dimensions") != null) continue; // Foo a[] is an array
            addTyped(src.text(name), typeNode, valueOf(vd), into, src);
        }
    }

    /** {@code for (T x : xs)} and {@code try (T r = ...)}, which carry the name directly. */
    private static void addNamed(TSNode n, Map<String, String> into, Source src, boolean needsValue) {
        TSNode value = valueOf(n);
        if (needsValue && value == null) return; // try (existing) names nothing new
        TSNode name = child(n, "identifier");
        TSNode typeNode = n.getType().equals("enhanced_for_statement") ? afterParen(n) : firstNonModifier(n);
        if (name != null && typeNode != null) addTyped(src.text(name), typeNode, needsValue ? value : null, into, src);
    }

    private static void addTyped(String name, TSNode typeNode, TSNode value, Map<String, String> into, Source src) {
        if (typeNode == null) return;
        if (typeNode.getType().equals("type_identifier") && src.text(typeNode).equals("var")) {
            // var x = new T(): the type is T; anything else is left unknown
            if (value != null && value.getType().equals("object_creation_expression")) {
                put(into, name, simpleTypeName(creationType(value), src));
            }
        } else {
            put(into, name, simpleTypeName(typeNode, src));
        }
    }

    private static void put(Map<String, String> m, String name, String type) {
        if (type != null) m.putIfAbsent(name, type);
    }

    private static TSNode firstNonModifier(TSNode decl) {
        for (int i = 0; i < decl.getChildCount(); i++) {
            TSNode c = decl.getChild(i);
            if (!c.getType().equals("modifiers")) return c;
        }
        return null;
    }

    private static TSNode afterParen(TSNode forEach) {
        boolean open = false;
        for (int i = 0; i < forEach.getChildCount(); i++) {
            TSNode c = forEach.getChild(i);
            if (!open) { open = c.getType().equals("("); continue; }
            if (!c.getType().equals("modifiers")) return c;
        }
        return null;
    }

    /** What follows the {@code =}, or null. */
    private static TSNode valueOf(TSNode n) {
        for (int i = 0; i + 1 < n.getChildCount(); i++) {
            if (n.getChild(i).getType().equals("=")) return n.getChild(i + 1);
        }
        return null;
    }

    private static TSNode lastChild(TSNode node, String type) {
        for (int i = node.getChildCount() - 1; i >= 0; i--) {
            if (type.equals(node.getChild(i).getType())) return node.getChild(i);
        }
        return null;
    }
}
