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
import org.treesitter.TreeSitterPython;

/**
 * Python, read with the tree-sitter grammar. Facts and definitions are the ones the pattern-based version
 * produced, so scores and graphs hardly move: the same imports and symbols, in source order.
 *
 * <p>Imports are reported as dotted module names. A relative import keeps its leading dots
 * ({@code from ..pkg import a} gives {@code ..pkg} and {@code ..pkg.a}), and {@code from pkg import name}
 * reports both {@code pkg} and {@code pkg.name}, because {@code name} may be a submodule; the graph
 * builder keeps whichever of them is a file in the repository. {@code from pkg import *} gives {@code pkg}
 * flagged as a wildcard. An import anywhere counts, inside a function or a {@code try} too.
 *
 * <p>Complexity follows the Java definition: one point per {@code def} (methods, nested functions and
 * {@code async def}; a lambda is not one), plus one per decision point: {@code if}, {@code elif},
 * {@code for}, {@code while}, {@code except}, each {@code case} (the {@code _} case too), {@code and},
 * {@code or}, and the {@code for} and {@code if} of a comprehension or a conditional expression. The
 * {@code if} guard of a {@code case} counts as well, which the pattern-based version missed.
 *
 * <p>Python 2 code and other files with a syntax error are still read, as far as the grammar can.
 *
 * <p>Call graph facts (see {@link TreeSitterCallSupport}): every {@code def}, owned by the class it sits
 * directly in ({@code ""} for a module-level function or a helper nested in another function; {@code self}
 * and {@code cls} are not counted as parameters; {@code __init__} is named after its class, so that
 * {@code Foo(...)} finds it), and every call made from inside one. The receiver of a call is
 * {@code ""} for {@code self.f()}, {@code cls.f()} and {@code f()}; the class name for {@code Foo.f()},
 * for a variable known to hold a {@code Foo} (a parameter annotated {@code x: Foo}, {@code x = Foo()}), and
 * for {@code self.attr.f()} when {@code attr} is annotated or assigned a {@code Foo} in the class; the
 * last name of the module for {@code mod.f()} and {@code pkg.mod.f()}. An import alias is replaced by the
 * name it stands for ({@code from a import B as C} makes {@code C()} a call of {@code B}). Anything else is
 * unknown.
 */
@Component
public class PythonLanguageSupport extends TreeSitterCallSupport {

    private static final Set<String> FUNCTIONS = Set.of("function_definition");
    private static final Set<String> DECISIONS = Set.of(
            "if_statement", "elif_clause", "for_statement", "while_statement", "except_clause",
            "except_group_clause", "case_clause", "conditional_expression", "for_in_clause", "if_clause",
            "and", "or");

    @Override
    public String language() { return "python"; }

    @Override
    protected TSLanguage grammar() { return new TreeSitterPython(); }

    @Override
    protected Set<String> functionTypes() { return FUNCTIONS; }

    @Override
    protected Set<String> decisionTypes() { return DECISIONS; }

    /** A string is one token, and what is inside it (an f-string's expressions too) is never read. */
    @Override
    protected boolean isAtomic(String type) { return type.equals("string"); }

    @Override
    protected void visit(TSNode node, String type, Source src, Collected out) {
        switch (type) {
            case "import_statement" -> importStatement(node, src, out);
            case "import_from_statement", "future_import_statement" -> importFrom(node, src, out);
            case "class_definition" -> addSymbol(node, "CLASS", src, out);
            case "function_definition" -> addSymbol(node, "METHOD", src, out);
            default -> { }
        }
    }

    /** The symbol sits on the line of its name, so a decorator or {@code async} above it does not move it. */
    private static void addSymbol(TSNode node, String kind, Source src, Collected out) {
        TSNode name = child(node, "identifier");
        if (name != null) out.symbols.add(new Symbol(src.text(name), kind, src.line(name)));
    }

    /** {@code import a.b, c as d}: the module names, without the aliases. */
    private static void importStatement(TSNode node, Source src, Collected out) {
        for (int i = 0; i < node.getChildCount(); i++) {
            TSNode c = node.getChild(i);
            String name = importedName(c, src);
            if (name != null) add(out, name, false);
        }
    }

    /** {@code from <module> import <names>}; the module is the part before the {@code import} keyword. */
    private static void importFrom(TSNode node, Source src, Collected out) {
        TSNode moduleNode = null;
        boolean afterImport = false;
        boolean wildcard = false;
        java.util.List<String> names = new java.util.ArrayList<>();
        for (int i = 0; i < node.getChildCount(); i++) {
            TSNode c = node.getChild(i);
            String t = c.getType();
            if (!afterImport) {
                if (t.equals("import")) afterImport = true;
                else if (t.equals("dotted_name") || t.equals("relative_import") || t.equals("__future__")) moduleNode = c;
            } else if (t.equals("wildcard_import")) {
                wildcard = true;
            } else {
                String name = importedName(c, src);
                if (name != null) names.add(name);
            }
        }
        if (moduleNode == null) return;
        String module = src.text(moduleNode).replaceAll("\\s+", "");
        if (module.isEmpty()) return;
        if (wildcard) {
            add(out, module, true);
            return;
        }
        if (names.isEmpty()) return;
        add(out, module, false);
        for (String name : names) {
            add(out, module.endsWith(".") ? module + name : module + "." + name, false);
        }
    }

    /** The dotted name of a {@code dotted_name} or of an {@code aliased_import}; null for anything else. */
    private static String importedName(TSNode node, Source src) {
        TSNode name = switch (node.getType()) {
            case "dotted_name" -> node;
            case "aliased_import" -> child(node, "dotted_name");
            default -> null;
        };
        if (name == null) return null;
        String text = src.text(name).replaceAll("\\s+", "");
        return text.isEmpty() ? null : text;
    }

    /** Each module is reported once, a wildcard separately from a plain import of the same module. */
    private static void add(Collected out, String name, boolean wildcard) {
        boolean seen = out.imports.stream().anyMatch(i -> i.name().equals(name) && i.isWildcard() == wildcard);
        if (!seen) out.imports.add(new RawImport(name, false, wildcard));
    }

    // ---- call graph facts ----

    private static final String SUPER = "super";
    private static final Set<String> SELF_NAMES = Set.of("self", "cls", "mcs");
    private static final Set<String> NOT_ARGUMENTS = Set.of("(", ")", ",", "comment");
    private static final Set<String> WRAPPERS = Set.of("Optional", "Final", "ClassVar", "Annotated", "Required",
            "NotRequired", "Union");
    private static final Set<String> PARAMETERS = Set.of(
            "identifier", "typed_parameter", "default_parameter", "typed_default_parameter", "tuple_pattern");

    @Override
    protected boolean opensType(String nodeType) { return nodeType.equals("class_definition"); }

    @Override
    protected String typeName(TSNode node, Source src) {
        TSNode name = child(node, "identifier");
        return name == null ? null : src.text(name);
    }

    @Override
    protected Callable openCallable(TSNode node, String type, TypeCtx types, boolean nested, FileCtx file,
                                    Source src) {
        if (!type.equals("function_definition")) return null;
        TSNode nameNode = child(node, "identifier");
        if (nameNode == null) return null;
        String owner = nested || types == null ? "" : types.name();
        String name = src.text(nameNode);
        if (!owner.isEmpty() && name.equals("__init__")) name = owner; // the constructor is named after its class
        int arity = parameterCount(child(node, "parameters"), !owner.isEmpty(), src);
        return new Callable(node, owner, name, arity, src.line(node), types);
    }

    /** Parameters other than {@code self} / {@code cls} and the starred ones. */
    private static int parameterCount(TSNode params, boolean inClass, Source src) {
        if (params == null) return 0;
        int n = 0;
        boolean first = true;
        for (int i = 0; i < params.getChildCount(); i++) {
            TSNode p = params.getChild(i);
            if (!PARAMETERS.contains(p.getType())) continue;
            boolean isFirst = first;
            first = false;
            if (inClass && isFirst && SELF_NAMES.contains(src.text(parameterName(p)))) continue;
            n++;
        }
        return n;
    }

    /** The identifier that names a parameter, whatever its shape. */
    private static TSNode parameterName(TSNode p) {
        if (p.getType().equals("identifier")) return p;
        TSNode id = child(p, "identifier");
        return id != null ? id : p;
    }

    @Override
    protected void callsOf(TSNode node, String type, Callable caller, FileCtx file, Source src,
                           List<CallSite> into) {
        if (!type.equals("call") || node.getChildCount() < 2) return;
        TSNode fn = node.getChild(0);
        int arity = argumentCount(node.getChild(node.getChildCount() - 1));
        if (fn.getType().equals("identifier")) {
            String name = file.aliases.getOrDefault(src.text(fn), src.text(fn));
            if (name.equals(SUPER)) return;
            // Foo(...) builds a Foo; the receiver says which class's constructor is meant
            into.add(new CallSite(caller.type, caller.name, caller.arity, name, arity, isUpper(name) ? name : ""));
        } else if (fn.getType().equals("attribute") && fn.getChildCount() >= 3) {
            TSNode attr = fn.getChild(fn.getChildCount() - 1);
            if (!attr.getType().equals("identifier")) return;
            String receiver = receiverOf(fn.getChild(0), caller, file, src);
            if (SUPER.equals(receiver)) return;
            into.add(new CallSite(caller.type, caller.name, caller.arity, src.text(attr), arity, receiver));
        }
    }

    private static int argumentCount(TSNode args) {
        if (!args.getType().equals("argument_list")) return 1; // f(x for x in xs)
        return countExcept(args, NOT_ARGUMENTS);
    }

    /** "" for self, SUPER for super(), a type or module name where it can be told cheaply, otherwise null. */
    private String receiverOf(TSNode obj, Callable caller, FileCtx file, Source src) {
        switch (obj.getType()) {
            case "identifier" -> {
                String id = src.text(obj);
                if (SELF_NAMES.contains(id)) return "";
                String t = scope(caller, file, src).locals().get(id);
                if (t != null) return t;
                String alias = file.aliases.get(id);
                if (alias != null) return alias;
                return isUpper(id) ? id : null; // Util.f(): a class
            }
            case "attribute" -> {
                TSNode root = obj.getChild(0);
                TSNode attr = obj.getChild(obj.getChildCount() - 1);
                if (!attr.getType().equals("identifier")) return null;
                String name = src.text(attr);
                if (root.getType().equals("identifier") && SELF_NAMES.contains(src.text(root))) {
                    return scope(caller, file, src).fields().get(name); // self.repo.f()
                }
                TSNode base = root;
                while (base.getType().equals("attribute") && base.getChildCount() > 0) base = base.getChild(0);
                if (base.getType().equals("identifier") && file.aliases.containsKey(src.text(base))) {
                    return name; // pkg.mod.f(): the module is the last name of the path
                }
                return isUpper(name) && isUpper(src.text(base)) ? name : null; // Outer.Inner.f()
            }
            case "call" -> {
                TSNode fn = obj.getChild(0);
                return fn.getType().equals("identifier") && src.text(fn).equals(SUPER) ? SUPER : null;
            }
            default -> { return null; }
        }
    }

    // ---- what names stand for ----

    @Override
    protected FileCtx beginFile(TSNode root, Source src) {
        FileCtx file = new FileCtx();
        walk(root, n -> {
            switch (n.getType()) {
                case "import_statement" -> {
                    for (int i = 0; i < n.getChildCount(); i++) addAlias(n.getChild(i), true, src, file);
                }
                case "import_from_statement" -> {
                    boolean afterImport = false;
                    for (int i = 0; i < n.getChildCount(); i++) {
                        TSNode c = n.getChild(i);
                        if (c.getType().equals("import")) afterImport = true;
                        else if (afterImport) addAlias(c, false, src, file);
                    }
                }
                default -> { }
            }
        });
        return file;
    }

    /**
     * {@code import a.b} binds {@code a}; {@code import a.b as x} binds {@code x} to {@code b};
     * {@code from p import m as x} binds {@code x} to {@code m}. A name that is not an import is ignored.
     */
    private static void addAlias(TSNode c, boolean plainImport, Source src, FileCtx file) {
        TSNode dotted = c.getType().equals("dotted_name") ? c : child(c, "dotted_name");
        if (dotted == null || dotted.getChildCount() == 0) return;
        String full = src.text(dotted).replaceAll("\\s+", "");
        String alias = null;
        if (c.getType().equals("aliased_import")) {
            TSNode a = lastChild(c, "identifier");
            if (a != null) alias = src.text(a);
        }
        if (alias != null) {
            file.aliases.put(alias, lastSegment(full));
        } else if (plainImport) {
            String first = full.contains(".") ? full.substring(0, full.indexOf('.')) : full;
            file.aliases.put(first, first);
        } else {
            file.aliases.put(lastSegment(full), lastSegment(full));
        }
    }

    @Override
    protected Scope scopeOf(Callable c, FileCtx file, Source src) {
        Map<String, String> locals = new HashMap<>();
        addParameterTypes(child(c.node, "parameters"), locals, src);
        walk(c.node, n -> {
            if (!n.getType().equals("assignment") || n.getChildCount() < 3) return;
            TSNode left = n.getChild(0);
            if (!left.getType().equals("identifier")) return;
            putIfKnown(locals, src.text(left), assignedType(n, file, src));
        });
        Map<String, String> fields = new HashMap<>();
        for (TypeCtx t = c.types; t != null; t = t.parent()) {
            for (var e : fieldsOf(t, file, src).entrySet()) fields.putIfAbsent(e.getKey(), e.getValue());
        }
        return new Scope(locals, fields);
    }

    /** {@code x: Foo = ...} gives Foo, and so does {@code x = Foo(...)}. */
    private static String assignedType(TSNode assignment, FileCtx file, Source src) {
        TSNode annotation = after(assignment, ":");
        if (annotation != null) return annotation(annotation, src);
        TSNode value = after(assignment, "=");
        if (value == null || !value.getType().equals("call") || value.getChildCount() == 0) return null;
        TSNode fn = value.getChild(0);
        if (!fn.getType().equals("identifier")) return null;
        String name = file.aliases.getOrDefault(src.text(fn), src.text(fn));
        return isUpper(name) ? name : null;
    }

    private static void addParameterTypes(TSNode params, Map<String, String> into, Source src) {
        if (params == null) return;
        for (int i = 0; i < params.getChildCount(); i++) {
            TSNode p = params.getChild(i);
            if (!p.getType().equals("typed_parameter") && !p.getType().equals("typed_default_parameter")) continue;
            TSNode name = child(p, "identifier");
            TSNode type = child(p, "type");
            if (name != null && type != null) putIfKnown(into, src.text(name), annotation(type, src));
        }
    }

    /** Annotated or constructed attributes of a class: {@code repo: Repo} in the body, {@code self.repo = ...}. */
    private Map<String, String> fieldsOf(TypeCtx t, FileCtx file, Source src) {
        return file.fieldsByType.computeIfAbsent(t.node().getStartByte(), k -> {
            Map<String, String> fields = new HashMap<>();
            TSNode body = child(t.node(), "block");
            if (body == null) return fields;
            for (int i = 0; i < body.getChildCount(); i++) {
                TSNode stmt = body.getChild(i);
                if (stmt.getType().equals("expression_statement")) {
                    TSNode a = child(stmt, "assignment");
                    if (a != null && a.getChildCount() >= 3 && a.getChild(0).getType().equals("identifier")) {
                        putIfKnown(fields, src.text(a.getChild(0)), assignedType(a, file, src));
                    }
                    continue;
                }
                TSNode fn = stmt.getType().equals("decorated_definition") ? child(stmt, "function_definition") : stmt;
                if (fn != null && fn.getType().equals("function_definition")) selfAssignments(fn, fields, file, src);
            }
            return fields;
        });
    }

    /** {@code self.x = Foo()}, {@code self.x: Foo = ...} and {@code self.x = param} for an annotated param. */
    private static void selfAssignments(TSNode fn, Map<String, String> fields, FileCtx file, Source src) {
        Map<String, String> params = new HashMap<>();
        addParameterTypes(child(fn, "parameters"), params, src);
        walk(fn, n -> {
            if (!n.getType().equals("assignment") || n.getChildCount() < 3) return;
            TSNode left = n.getChild(0);
            if (!left.getType().equals("attribute") || left.getChildCount() < 3) return;
            TSNode root = left.getChild(0);
            TSNode attr = left.getChild(left.getChildCount() - 1);
            if (!root.getType().equals("identifier") || !SELF_NAMES.contains(src.text(root))
                    || !attr.getType().equals("identifier")) return;
            String type = assignedType(n, file, src);
            if (type == null && after(n, ":") == null) {
                TSNode value = after(n, "=");
                if (value != null && value.getType().equals("identifier")) type = params.get(src.text(value));
            }
            putIfKnown(fields, src.text(attr), type);
        });
    }

    /** The class an annotation names: {@code Foo}, {@code "Foo"}, {@code Optional[Foo]}, {@code Foo | None}, {@code a.Foo}. */
    private static String annotation(TSNode t, Source src) {
        if (t == null) return null;
        switch (t.getType()) {
            case "type" -> { return t.getChildCount() == 0 ? null : annotation(t.getChild(0), src); }
            case "identifier" -> {
                String s = src.text(t);
                return s.equals("None") ? null : s;
            }
            case "attribute" -> {
                TSNode last = lastChild(t, "identifier");
                return last == null ? null : src.text(last);
            }
            case "string" -> {
                String s = src.text(t).replaceAll("^[\"']+|[\"']+$", "");
                return s.matches("\\w+") ? s : null;
            }
            case "generic_type" -> {
                TSNode base = t.getChildCount() == 0 ? null : t.getChild(0);
                String name = annotation(base, src);
                if (name == null) return null;
                TSNode args = child(t, "type_parameter");
                // Optional[Foo] is a Foo; List[Foo] is a List, which is not ours
                return WRAPPERS.contains(name) && args != null ? firstTypeArgument(args, src) : name;
            }
            case "binary_operator" -> {
                String left = annotation(t.getChild(0), src);
                return left != null ? left : annotation(t.getChild(t.getChildCount() - 1), src);
            }
            default -> { return null; }
        }
    }

    private static String firstTypeArgument(TSNode typeParameter, Source src) {
        for (int i = 0; i < typeParameter.getChildCount(); i++) {
            TSNode c = typeParameter.getChild(i);
            if (c.getType().equals("type")) {
                String t = annotation(c, src);
                if (t != null) return t;
            }
        }
        return null;
    }
}
