package com.saptarishi.backend.parse;

import com.saptarishi.backend.parse.ParsedFile.CallSite;
import com.saptarishi.backend.parse.ParsedFile.RawImport;
import com.saptarishi.backend.parse.ParsedFile.Symbol;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;
import org.treesitter.TSLanguage;
import org.treesitter.TSNode;
import org.treesitter.TreeSitterGo;

/**
 * Go. Imports are reported as the import path as written ({@code "example.com/app/util"}); turning a
 * path into files needs the module path from go.mod, which {@link GoModSupport} reads and
 * {@code GoImportResolver} uses. Structs and interfaces are CLASS and INTERFACE symbols; functions and
 * methods are METHOD symbols.
 *
 * <p>Complexity: one per function, method and function literal, plus one per {@code if}, {@code for},
 * {@code case} (expression, type and select), {@code &&} and {@code ||}. A {@code default} is not counted,
 * as in Java.
 *
 * <p>Call graph facts (see {@link TreeSitterCallSupport}): a method is owned by its receiver's type
 * ({@code Server} for {@code func (s *Server) Run()}), a function by nobody ({@code ""}: it belongs to the
 * package, and {@code CallGraphBuilder} lets a call reach every file of the same folder), and the methods of
 * an interface are listed under the interface. The receiver of a call is the package name for
 * {@code util.Do()} (an import alias is replaced by the last name of its path); the type for {@code x.Do()}
 * when {@code x} is a parameter, a receiver, a {@code var x T}, {@code x := T{}} / {@code &T{}} or
 * {@code x := NewT()}; and for {@code s.repo.Do()} the type of the field {@code repo} in a struct declared
 * in the same file. A function literal is not a function here: its calls belong to the function around it.
 */
@Component
public class GoLanguageSupport extends TreeSitterCallSupport {

    private static final Set<String> FUNCTIONS = Set.of("function_declaration", "method_declaration", "func_literal");
    private static final Set<String> DECISIONS = Set.of(
            "if_statement", "for_statement", "expression_case", "type_case", "communication_case", "&&", "||");

    @Override
    public String language() { return "go"; }

    @Override
    protected TSLanguage grammar() { return new TreeSitterGo(); }

    @Override
    protected Set<String> functionTypes() { return FUNCTIONS; }

    @Override
    protected Set<String> decisionTypes() { return DECISIONS; }

    @Override
    protected void visit(TSNode node, String type, Source src, Collected out) {
        switch (type) {
            case "import_spec" -> {
                TSNode lit = child(node, "interpreted_string_literal");
                if (lit == null) lit = child(node, "raw_string_literal");
                if (lit == null) return;
                String text = src.text(lit);
                if (text.length() >= 2) out.imports.add(new RawImport(text.substring(1, text.length() - 1), false, false));
            }
            case "type_spec" -> {
                TSNode name = child(node, "type_identifier");
                if (name == null) return;
                String kind = child(node, "struct_type") != null ? "CLASS"
                        : child(node, "interface_type") != null ? "INTERFACE" : null;
                if (kind != null) out.symbols.add(new Symbol(src.text(name), kind, src.line(name)));
            }
            case "function_declaration" -> addFunction(node, "identifier", src, out);
            case "method_declaration" -> addFunction(node, "field_identifier", src, out);
            default -> { }
        }
    }

    private static void addFunction(TSNode node, String nameType, Source src, Collected out) {
        TSNode name = child(node, nameType);
        if (name != null) out.symbols.add(new Symbol(src.text(name), "METHOD", src.line(name)));
    }

    // ---- call graph facts ----

    private static final Set<String> NOT_ARGUMENTS = Set.of("(", ")", ",", "comment");

    /** Per-file facts: the struct fields declared in this file, and the packages this file imports by name. */
    private static final class GoCtx extends FileCtx {
        final Map<String, Map<String, String>> structFields = new HashMap<>();
    }

    @Override
    protected boolean opensType(String nodeType) { return nodeType.equals("type_spec"); }

    @Override
    protected String typeName(TSNode node, Source src) {
        TSNode name = child(node, "type_identifier");
        return name == null ? null : src.text(name);
    }

    @Override
    protected Callable openCallable(TSNode node, String type, TypeCtx types, boolean nested, FileCtx file, Source src) {
        switch (type) {
            case "function_declaration" -> {
                TSNode name = child(node, "identifier");
                if (name == null) return null;
                return new Callable(node, "", src.text(name), parameterCount(child(node, "parameter_list")), src.line(node), types);
            }
            case "method_declaration" -> {
                TSNode name = child(node, "field_identifier");
                List<TSNode> lists = parameterLists(node);
                if (name == null || lists.size() < 2) return null;
                String owner = receiverType(lists.get(0), src);
                return new Callable(node, owner == null ? "" : owner, src.text(name), parameterCount(lists.get(1)), src.line(node), types);
            }
            case "method_elem" -> { // a method of an interface
                TSNode name = child(node, "field_identifier");
                if (name == null || types == null) return null;
                return new Callable(node, types.name(), src.text(name), parameterCount(child(node, "parameter_list")), src.line(node), types);
            }
            default -> { return null; }
        }
    }

    private static List<TSNode> parameterLists(TSNode node) {
        List<TSNode> out = new ArrayList<>();
        for (int i = 0; i < node.getChildCount(); i++) {
            if (node.getChild(i).getType().equals("parameter_list")) out.add(node.getChild(i));
        }
        return out;
    }

    /** Each name in {@code a, b int} counts, and so does a parameter with no name. */
    private static int parameterCount(TSNode list) {
        if (list == null) return 0;
        int n = 0;
        for (int i = 0; i < list.getChildCount(); i++) {
            TSNode p = list.getChild(i);
            if (!p.getType().equals("parameter_declaration") && !p.getType().equals("variadic_parameter_declaration")) continue;
            int names = 0;
            for (int j = 0; j < p.getChildCount(); j++) if (p.getChild(j).getType().equals("identifier")) names++;
            n += Math.max(1, names);
        }
        return n;
    }

    private static String receiverType(TSNode receiver, Source src) {
        for (int i = 0; i < receiver.getChildCount(); i++) {
            TSNode p = receiver.getChild(i);
            if (p.getType().equals("parameter_declaration")) return simpleType(lastTypeNode(p), src);
        }
        return null;
    }

    /** The type of a parameter or field declaration: its last child that is not a name or punctuation. */
    private static TSNode lastTypeNode(TSNode decl) {
        TSNode type = null;
        for (int i = 0; i < decl.getChildCount(); i++) {
            String t = decl.getChild(i).getType();
            if (!t.equals("identifier") && !t.equals("field_identifier") && !t.equals(",")
                    && !t.equals("raw_string_literal") && !t.equals("interpreted_string_literal")) {
                type = decl.getChild(i);
            }
        }
        return type;
    }

    /** {@code Foo}, {@code *Foo}, {@code pkg.Foo}, {@code Foo[T]}; null for slices, maps, functions and the like. */
    private static String simpleType(TSNode t, Source src) {
        if (t == null) return null;
        switch (t.getType()) {
            case "type_identifier" -> { return src.text(t); }
            case "pointer_type", "parenthesized_type" -> {
                return t.getChildCount() == 0 ? null : simpleType(t.getChild(t.getChildCount() - 1), src);
            }
            case "generic_type" -> { return simpleType(child(t, "type_identifier"), src); }
            case "qualified_type" -> { return simpleType(lastChild(t, "type_identifier"), src); }
            default -> { return null; }
        }
    }

    @Override
    protected void callsOf(TSNode node, String type, Callable caller, FileCtx file, Source src, List<CallSite> into) {
        if (!type.equals("call_expression") || node.getChildCount() < 2) return;
        TSNode fn = node.getChild(0);
        TSNode args = child(node, "argument_list");
        if (args == null) return;
        int arity = countExcept(args, NOT_ARGUMENTS);
        if (fn.getType().equals("identifier")) {
            into.add(new CallSite(caller.type, caller.name, caller.arity, src.text(fn), arity, ""));
        } else if (fn.getType().equals("selector_expression") && fn.getChildCount() >= 3) {
            TSNode field = fn.getChild(fn.getChildCount() - 1);
            if (!field.getType().equals("field_identifier")) return;
            into.add(new CallSite(caller.type, caller.name, caller.arity, src.text(field), arity,
                    receiverOf(fn.getChild(0), caller, (GoCtx) file, src)));
        }
    }

    /** The package, or the type of the variable or field, or null when it cannot be told cheaply. */
    private String receiverOf(TSNode operand, Callable caller, GoCtx file, Source src) {
        switch (operand.getType()) {
            case "identifier" -> {
                String id = src.text(operand);
                String t = scope(caller, file, src).locals().get(id);
                return t != null ? t : file.aliases.get(id);
            }
            case "selector_expression" -> { // s.repo.Do(): the type of the field repo of s
                TSNode root = operand.getChild(0);
                TSNode field = operand.getChild(operand.getChildCount() - 1);
                if (!root.getType().equals("identifier") || !field.getType().equals("field_identifier")) return null;
                String rootType = scope(caller, file, src).locals().get(src.text(root));
                Map<String, String> fields = rootType == null ? null : file.structFields.get(rootType);
                return fields == null ? null : fields.get(src.text(field));
            }
            case "parenthesized_expression" -> {
                return operand.getChildCount() < 3 ? null : receiverOf(operand.getChild(1), caller, file, src);
            }
            default -> { return null; }
        }
    }

    // ---- what names stand for ----

    @Override
    protected FileCtx beginFile(TSNode root, Source src) {
        GoCtx file = new GoCtx();
        walk(root, n -> {
            switch (n.getType()) {
                case "import_spec" -> addImport(n, src, file);
                case "type_spec" -> addStruct(n, src, file);
                default -> { }
            }
        });
        return file;
    }

    /** {@code import u "a/b/util"} binds u to util; {@code import "a/b/util"} binds util; a /v2 suffix is skipped. */
    private static void addImport(TSNode spec, Source src, GoCtx file) {
        TSNode lit = child(spec, "interpreted_string_literal");
        if (lit == null) lit = child(spec, "raw_string_literal");
        if (lit == null) return;
        String path = src.text(lit).replaceAll("^[\"`]|[\"`]$", "");
        String[] parts = path.split("/");
        String pkg = parts[parts.length - 1];
        if (pkg.matches("v[0-9]+") && parts.length > 1) pkg = parts[parts.length - 2];
        TSNode alias = child(spec, "package_identifier");
        String bound = alias != null ? src.text(alias) : pkg;
        if (!bound.equals("_") && !bound.equals(".")) file.aliases.put(bound, pkg);
    }

    private static void addStruct(TSNode spec, Source src, GoCtx file) {
        TSNode name = child(spec, "type_identifier");
        TSNode struct = child(spec, "struct_type");
        TSNode list = struct == null ? null : child(struct, "field_declaration_list");
        if (name == null || list == null) return;
        Map<String, String> fields = new HashMap<>();
        for (int i = 0; i < list.getChildCount(); i++) {
            TSNode f = list.getChild(i);
            if (!f.getType().equals("field_declaration")) continue;
            String type = simpleType(lastTypeNode(f), src);
            boolean named = false;
            for (int j = 0; j < f.getChildCount(); j++) {
                if (f.getChild(j).getType().equals("field_identifier")) {
                    putIfKnown(fields, src.text(f.getChild(j)), type);
                    named = true;
                }
            }
            if (!named) putIfKnown(fields, type, type); // an embedded type is a field named after itself
        }
        file.structFields.put(src.text(name), fields);
    }

    @Override
    protected Scope scopeOf(Callable c, FileCtx file, Source src) {
        Map<String, String> locals = new HashMap<>();
        List<TSNode> lists = parameterLists(c.node);
        if (c.node.getType().equals("method_declaration") && lists.size() >= 2) {
            addParameters(lists.get(0), locals, src); // the receiver: s *Server
            addParameters(lists.get(1), locals, src);
        } else if (!lists.isEmpty()) {
            addParameters(lists.get(0), locals, src);
        }
        walk(c.node, n -> {
            switch (n.getType()) {
                case "var_spec" -> {
                    TSNode type = lastTypeNode(n);
                    if (type == null) return;
                    for (int i = 0; i < n.getChildCount(); i++) {
                        if (n.getChild(i).getType().equals("identifier")) putIfKnown(locals, src.text(n.getChild(i)), simpleType(type, src));
                    }
                }
                case "short_var_declaration" -> addShortDeclaration(n, locals, src);
                default -> { }
            }
        });
        return new Scope(locals, Map.of());
    }

    private static void addParameters(TSNode list, Map<String, String> into, Source src) {
        for (int i = 0; i < list.getChildCount(); i++) {
            TSNode p = list.getChild(i);
            if (!p.getType().equals("parameter_declaration") && !p.getType().equals("variadic_parameter_declaration")) continue;
            String type = simpleType(lastTypeNode(p), src);
            for (int j = 0; j < p.getChildCount(); j++) {
                if (p.getChild(j).getType().equals("identifier")) putIfKnown(into, src.text(p.getChild(j)), type);
            }
        }
    }

    /** {@code x := T{}}, {@code x := &T{}}, {@code x := NewT()}; several names pair up with several values. */
    private static void addShortDeclaration(TSNode n, Map<String, String> into, Source src) {
        TSNode left = child(n, "expression_list");
        TSNode right = lastChild(n, "expression_list");
        if (left == null || right == null || left == right) return;
        List<TSNode> names = new ArrayList<>();
        List<TSNode> values = new ArrayList<>();
        for (int i = 0; i < left.getChildCount(); i++) if (left.getChild(i).getType().equals("identifier")) names.add(left.getChild(i));
        for (int i = 0; i < right.getChildCount(); i++) if (!right.getChild(i).getType().equals(",")) values.add(right.getChild(i));
        if (names.size() != values.size()) return;
        for (int i = 0; i < names.size(); i++) putIfKnown(into, src.text(names.get(i)), valueType(values.get(i), src));
    }

    private static String valueType(TSNode v, Source src) {
        switch (v.getType()) {
            case "composite_literal" -> { return simpleType(v.getChildCount() == 0 ? null : v.getChild(0), src); }
            case "unary_expression" -> { // &T{}
                TSNode operand = v.getChild(v.getChildCount() - 1);
                return operand.getType().equals("composite_literal") ? valueType(operand, src) : null;
            }
            case "call_expression" -> { // NewT(...) or pkg.NewT(...) builds a T, by convention
                TSNode fn = v.getChild(0);
                TSNode name = fn.getType().equals("identifier") ? fn : fn.getType().equals("selector_expression") ? lastChild(fn, "field_identifier") : null;
                if (name == null) return null;
                String s = src.text(name);
                return s.length() > 3 && s.startsWith("New") && Character.isUpperCase(s.charAt(3)) ? s.substring(3) : null;
            }
            default -> { return null; }
        }
    }
}
