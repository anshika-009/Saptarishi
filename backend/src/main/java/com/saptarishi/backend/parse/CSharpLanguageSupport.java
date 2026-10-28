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
import org.treesitter.TreeSitterCSharp;

/**
 * C#. A file's namespace is its {@code packageName}; {@code using} directives are its imports, as the
 * namespace named (for an alias or {@code using static} that is a type, and the resolver then looks at the
 * namespace around it). A {@code global using} is marked with {@code isWildcard}, meaning "applies to every
 * file". Every identifier the file mentions is kept in {@code typeRefs}, because a namespace is not a file:
 * the graph links a file to those files of an imported namespace that declare a type it mentions.
 *
 * <p>Complexity: one per method, constructor, local function and lambda, plus one per {@code if}, loop,
 * {@code catch}, {@code ?:}, {@code case} label, switch-expression arm other than {@code _}, {@code &&}
 * and {@code ||}. A {@code default} is not counted, as in Java.
 *
 * <p>Call graph facts (see {@link TreeSitterCallSupport}): methods, constructors (named after their type, as
 * in the declaration) and local functions (owned by nobody), including the bodiless methods of interfaces
 * and abstract classes so a call through an interface has somewhere to land. The receiver of a call is
 * {@code ""} for {@code this.f()} and {@code f()}; the type for {@code Util.f()}, {@code new T().f()}, a
 * parameter or local declared {@code T x} (or {@code var x = new T()}), a field or property of the class
 * (and a primary-constructor parameter), and {@code ns.T.f()}. {@code base.f()} is skipped.
 */

@Component
public class CSharpLanguageSupport extends TreeSitterCallSupport {

    private static final Set<String> FUNCTIONS = Set.of(
            "method_declaration", "constructor_declaration", "destructor_declaration", "operator_declaration",
            "conversion_operator_declaration", "local_function_statement", "lambda_expression",
            "anonymous_method_expression");
    private static final Set<String> DECISIONS = Set.of(
            "if_statement", "for_statement", "foreach_statement", "while_statement", "do_statement",
            "catch_clause", "conditional_expression", "&&", "||");

    @Override
    public String language() { return "csharp"; }

    @Override
    protected TSLanguage grammar() { return new TreeSitterCSharp(); }

    @Override
    protected Set<String> functionTypes() { return FUNCTIONS; }

    @Override
    protected Set<String> decisionTypes() { return DECISIONS; }

    @Override
    protected boolean isDecision(TSNode node, String type, Source src) {
        return switch (type) {
            case "switch_section" -> node.getChildCount() > 0 && "case".equals(node.getChild(0).getType());
            case "switch_expression_arm" -> node.getChildCount() > 0 && !"discard".equals(node.getChild(0).getType());
            default -> super.isDecision(node, type, src);
        };
    }

    @Override
    protected void visit(TSNode node, String type, Source src, Collected out) {
        switch (type) {
            case "identifier" -> out.addTypeRef(src.text(node));
            case "using_directive" -> addUsing(node, src, out);
            case "namespace_declaration", "file_scoped_namespace_declaration" -> {
                if (!out.packageName.isEmpty()) return;
                TSNode name = lastNameChild(node, true);
                if (name != null) out.packageName = src.text(name);
            }
            case "class_declaration", "struct_declaration" -> addType(node, "CLASS", src, out);
            case "interface_declaration" -> addType(node, "INTERFACE", src, out);
            case "enum_declaration" -> addType(node, "ENUM", src, out);
            case "record_declaration", "record_struct_declaration" -> addType(node, "RECORD", src, out);
            case "method_declaration" -> {
                // the name is the identifier just before the parameter list (the identifier before it is the return type)
                TSNode name = null;
                for (int i = 0; i < node.getChildCount(); i++) {
                    TSNode c = node.getChild(i);
                    String t = c.getType();
                    if (t.equals("parameter_list") || t.equals("type_parameter_list")) break;
                    if (t.equals("identifier")) name = c;
                }
                if (name != null) out.symbols.add(new Symbol(src.text(name), "METHOD", src.line(name)));
            }
            default -> { }
        }
    }

    private static void addUsing(TSNode node, Source src, Collected out) {
        boolean global = false;
        boolean isStatic = false;
        for (int i = 0; i < node.getChildCount(); i++) {
            String t = node.getChild(i).getType();
            if (t.equals("global")) global = true;
            if (t.equals("static")) isStatic = true;
        }
        TSNode name = lastNameChild(node, false);
        if (name != null) out.imports.add(new RawImport(src.text(name), isStatic, global));
    }

    private static void addType(TSNode node, String kind, Source src, Collected out) {
        TSNode name = child(node, "identifier");
        if (name != null) out.symbols.add(new Symbol(src.text(name), kind, src.line(name)));
    }

    /**
     * The last direct child that is a name. For {@code using A = X.Y;} that is the target, not the alias.
     * With {@code firstOnly} the first one instead (a namespace's own name).
     */
    private static TSNode lastNameChild(TSNode node, boolean firstOnly) {
        TSNode found = null;
        for (int i = 0; i < node.getChildCount(); i++) {
            TSNode c = node.getChild(i);
            String t = c.getType();
            if (t.equals("qualified_name") || t.equals("identifier") || t.equals("alias_qualified_name")) {
                found = c;
                if (firstOnly) break;
            }
        }
        return found;
    }

    // ---- call graph facts ----

    private static final Set<String> TYPE_DECLARATIONS = Set.of(
            "class_declaration", "struct_declaration", "interface_declaration", "record_declaration",
            "record_struct_declaration");
    private static final Set<String> NOT_ARGUMENTS = Set.of("(", ")", ",", "comment");
    private static final Set<String> FOREACH_PARTS = Set.of(
            "identifier", "generic_name", "qualified_name", "implicit_type", "predefined_type", "array_type",
            "nullable_type");

    @Override
    protected boolean opensType(String nodeType) { return TYPE_DECLARATIONS.contains(nodeType); }

    @Override
    protected String typeName(TSNode node, Source src) {
        TSNode name = child(node, "identifier");
        return name == null ? null : src.text(name);
    }

    @Override
    protected Callable openCallable(TSNode node, String type, TypeCtx types, boolean nested, FileCtx file, Source src) {
        switch (type) {
            case "method_declaration", "local_function_statement" -> {
                TSNode name = declaredName(node);
                if (name == null) return null;
                String owner = type.equals("local_function_statement") || nested || types == null ? "" : types.name();
                return new Callable(node, owner, src.text(name), parameterCount(child(node, "parameter_list")), src.line(node), types);
            }
            case "constructor_declaration" -> {
                TSNode name = child(node, "identifier");
                if (name == null || types == null) return null;
                return new Callable(node, types.name(), src.text(name), parameterCount(child(node, "parameter_list")), src.line(node), types);
            }
            default -> { return null; }
        }
    }

    /** The name is the last identifier before the parameter list: the identifier before it may be the return type. */
    private static TSNode declaredName(TSNode node) {
        TSNode name = null;
        for (int i = 0; i < node.getChildCount(); i++) {
            TSNode c = node.getChild(i);
            String t = c.getType();
            if (t.equals("parameter_list") || t.equals("type_parameter_list")) break;
            if (t.equals("identifier")) name = c;
        }
        return name;
    }

    /** This grammar leaves a {@code params} parameter unwrapped, as a bare {@code params} token. */
    private static int parameterCount(TSNode list) {
        if (list == null) return 0;
        int n = 0;
        for (int i = 0; i < list.getChildCount(); i++) {
            String t = list.getChild(i).getType();
            if (t.equals("parameter") || t.equals("params")) n++;
        }
        return n;
    }

    @Override
    protected void callsOf(TSNode node, String type, Callable caller, FileCtx file, Source src, List<CallSite> into) {
        if (type.equals("object_creation_expression")) {
            TSNode created = child(node, "identifier");
            if (created == null) created = childOf(node, Set.of("generic_name", "qualified_name"));
            String name = simpleType(created, src);
            TSNode args = child(node, "argument_list");
            if (name != null) {
                into.add(new CallSite(caller.type, caller.name, caller.arity, name, args == null ? 0 : countExcept(args, NOT_ARGUMENTS), name));
            }
            return;
        }
        if (!type.equals("invocation_expression") || node.getChildCount() < 2) return;
        TSNode fn = node.getChild(0);
        TSNode args = child(node, "argument_list");
        if (args == null) return;
        int arity = countExcept(args, NOT_ARGUMENTS);
        switch (fn.getType()) {
            case "identifier", "generic_name" -> {
                TSNode name = fn.getType().equals("identifier") ? fn : child(fn, "identifier");
                if (name != null) into.add(new CallSite(caller.type, caller.name, caller.arity, src.text(name), arity, ""));
            }
            case "member_access_expression" -> {
                TSNode name = nameOf(fn.getChild(fn.getChildCount() - 1));
                TSNode target = fn.getChildCount() >= 3 ? fn.getChild(0) : null;
                if (name == null || target == null) return;
                String receiver = receiverOf(target, caller, file, src);
                if ("base".equals(receiver)) return;
                into.add(new CallSite(caller.type, caller.name, caller.arity, src.text(name), arity, receiver));
            }
            case "conditional_access_expression" -> { // x?.f()
                TSNode binding = lastChild(fn, "member_binding_expression");
                TSNode name = binding == null ? null : nameOf(binding.getChild(binding.getChildCount() - 1));
                if (name == null || fn.getChildCount() == 0) return;
                into.add(new CallSite(caller.type, caller.name, caller.arity, src.text(name), arity,
                        receiverOf(fn.getChild(0), caller, file, src)));
            }
            default -> { }
        }
    }

    /** The identifier of a plain or generic name ({@code f} or {@code f<T>}). */
    private static TSNode nameOf(TSNode n) {
        if (n.getType().equals("identifier")) return n;
        return n.getType().equals("generic_name") ? child(n, "identifier") : null;
    }

    /** "" for this, "base" for base, a type where it can be told cheaply, otherwise null. */
    private String receiverOf(TSNode e, Callable caller, FileCtx file, Source src) {
        switch (e.getType()) {
            case "this" -> { return ""; }
            case "base" -> { return "base"; }
            case "identifier" -> {
                String id = src.text(e);
                String t = scope(caller, file, src).typeOf(id);
                if (t != null) return t;
                return isUpper(id) ? id : null; // Util.f(): a type
            }
            case "member_access_expression" -> {
                TSNode name = nameOf(e.getChild(e.getChildCount() - 1));
                if (name == null || e.getChildCount() < 3) return null;
                if (e.getChild(0).getType().equals("this")) return scope(caller, file, src).fields().get(src.text(name));
                return isUpper(src.text(name)) ? src.text(name) : null; // ns.Util.f(), or a property named like its type
            }
            case "parenthesized_expression" -> { return e.getChildCount() < 3 ? null : receiverOf(e.getChild(1), caller, file, src); }
            case "cast_expression" -> { return e.getChildCount() < 4 ? null : simpleType(e.getChild(1), src); }
            case "object_creation_expression" -> {
                TSNode created = child(e, "identifier");
                return created == null ? simpleType(childOf(e, Set.of("generic_name", "qualified_name")), src) : simpleType(created, src);
            }
            default -> { return null; }
        }
    }

    /** {@code T}, {@code T<U>}, {@code ns.T}, {@code T?}; null for predefined types, arrays and {@code var}. */
    private static String simpleType(TSNode t, Source src) {
        if (t == null) return null;
        switch (t.getType()) {
            case "identifier" -> { return src.text(t); }
            case "generic_name" -> { return simpleType(child(t, "identifier"), src); }
            case "qualified_name" -> { return simpleType(t.getChild(t.getChildCount() - 1), src); }
            case "nullable_type" -> { return t.getChildCount() == 0 ? null : simpleType(t.getChild(0), src); }
            default -> { return null; }
        }
    }

    @Override
    protected Scope scopeOf(Callable c, FileCtx file, Source src) {
        Map<String, String> locals = new HashMap<>();
        TSNode params = child(c.node, "parameter_list");
        if (params != null) addParameters(params, locals, src);
        walk(c.node, n -> {
            switch (n.getType()) {
                case "variable_declaration" -> addDeclarators(n, locals, src);
                case "foreach_statement" -> { // foreach (Item it in items): the type, then the variable, then the source
                    List<TSNode> parts = new ArrayList<>();
                    for (int k = 0; k < n.getChildCount(); k++) {
                        if (FOREACH_PARTS.contains(n.getChild(k).getType())) parts.add(n.getChild(k));
                    }
                    if (parts.size() >= 2 && parts.get(1).getType().equals("identifier")) {
                        putIfKnown(locals, src.text(parts.get(1)), simpleType(parts.get(0), src));
                    }
                }
                default -> { }
            }
        });
        Map<String, String> fields = new HashMap<>();
        for (TypeCtx t = c.types; t != null; t = t.parent()) {
            for (var e : fieldsOf(t, src, file).entrySet()) fields.putIfAbsent(e.getKey(), e.getValue());
        }
        return new Scope(locals, fields);
    }

    private static void addParameters(TSNode list, Map<String, String> into, Source src) {
        for (int i = 0; i < list.getChildCount(); i++) {
            TSNode p = list.getChild(i);
            if (!p.getType().equals("parameter")) continue;
            TSNode name = lastChild(p, "identifier");
            TSNode type = childOf(p, Set.of("identifier", "generic_name", "qualified_name", "nullable_type"));
            if (name != null && type != null && type != name) putIfKnown(into, src.text(name), simpleType(type, src));
        }
    }

    /** {@code T a = ..., b;} and {@code var a = new T();}. */
    private static void addDeclarators(TSNode decl, Map<String, String> into, Source src) {
        TSNode typeNode = decl.getChildCount() == 0 ? null : decl.getChild(0);
        boolean implicit = typeNode != null && typeNode.getType().equals("implicit_type");
        for (int i = 0; i < decl.getChildCount(); i++) {
            TSNode d = decl.getChild(i);
            if (!d.getType().equals("variable_declarator")) continue;
            TSNode name = child(d, "identifier");
            if (name == null) continue;
            if (implicit) {
                TSNode init = child(d, "object_creation_expression");
                if (init != null) putIfKnown(into, src.text(name), createdType(init, src));
            } else {
                putIfKnown(into, src.text(name), simpleType(typeNode, src));
            }
        }
    }

    private static String createdType(TSNode creation, Source src) {
        TSNode created = child(creation, "identifier");
        return created != null ? simpleType(created, src) : simpleType(childOf(creation, Set.of("generic_name", "qualified_name")), src);
    }

    /** Fields, properties and primary-constructor parameters of a type. */
    private Map<String, String> fieldsOf(TypeCtx t, Source src, FileCtx file) {
        return file.fieldsByType.computeIfAbsent(t.node().getStartByte(), k -> {
            Map<String, String> fields = new HashMap<>();
            TSNode primary = child(t.node(), "parameter_list");
            if (primary != null) addParameters(primary, fields, src);
            TSNode body = child(t.node(), "declaration_list");
            if (body == null) return fields;
            for (int i = 0; i < body.getChildCount(); i++) {
                TSNode m = body.getChild(i);
                if (m.getType().equals("field_declaration")) {
                    TSNode decl = child(m, "variable_declaration");
                    if (decl != null) addDeclarators(decl, fields, src);
                } else if (m.getType().equals("property_declaration")) {
                    TSNode type = childOf(m, Set.of("identifier", "generic_name", "qualified_name", "nullable_type"));
                    TSNode name = lastIdentifierBefore(m, "accessor_list", "arrow_expression_clause");
                    if (type != null && name != null && type != name) putIfKnown(fields, src.text(name), simpleType(type, src));
                }
            }
            return fields;
        });
    }

    /** The last identifier before a child of one of these types: a property's name, after its type. */
    private static TSNode lastIdentifierBefore(TSNode node, String stopA, String stopB) {
        TSNode name = null;
        for (int i = 0; i < node.getChildCount(); i++) {
            String t = node.getChild(i).getType();
            if (t.equals(stopA) || t.equals(stopB) || t.equals("=")) break;
            if (t.equals("identifier")) name = node.getChild(i);
        }
        return name;
    }
}
