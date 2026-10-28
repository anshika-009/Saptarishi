package com.saptarishi.backend.parse;

import com.saptarishi.backend.parse.ParsedFile.CallSite;
import com.saptarishi.backend.parse.ParsedFile.RawImport;
import com.saptarishi.backend.parse.ParsedFile.Symbol;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.treesitter.TSNode;

/**
 * JavaScript and TypeScript, read with a tree-sitter grammar. The subclasses only choose the grammar;
 * everything else is shared because the two grammars use the same node types for these constructs.
 *
 * <p>Imports are reported as the specifier as written ({@code "./util"}, {@code "react"}); turning a
 * specifier into a file is {@code ScriptImportResolver}'s job. They come from {@code import} and
 * {@code export ... from} statements, from {@code require('x')} (only a bare {@code require}, so
 * {@code obj.require('x')} is ignored) and from dynamic {@code import('x')}.
 *
 * <p>Complexity follows the Java definition: one point per function plus one per decision point. A
 * function is a declaration, a method, or an arrow or function expression that is assigned to a name
 * (a variable or a class field). A callback such as {@code items.map(x => x + 1)} is not counted. Each
 * {@code case} counts but {@code default} does not, and {@code ??} counts like {@code &&} and {@code ||}.
 *
 * <p>Call graph facts (see {@link TreeSitterCallSupport}). A function is owned by the class it is declared in
 * ({@code ""} for a plain function, a function in an object literal and anything nested in another
 * function); a {@code constructor} is named after its class. Besides declarations and methods, these are
 * named functions: {@code const f = () => ...}, a class field holding a function, {@code key: function ()
 * {}} in an object, and {@code exports.f = ...} / {@code Foo.prototype.f = ...}. Interface and abstract
 * method signatures are listed too, so a call through an interface has somewhere to land.
 *
 * <p>The receiver of a call is {@code ""} for {@code this.f()} and {@code f()}; the class for {@code Foo.f()},
 * {@code new Foo().f()}, a variable known to hold one ({@code const x = new Foo()}, or in TypeScript
 * {@code x: Foo}), and {@code this.attr.f()} when the field is typed, assigned {@code new Foo()} or is a
 * constructor parameter property; the module name for {@code ns.f()} after {@code import * as ns from
 * './ns'} or {@code const ns = require('./ns')}. Named imports are replaced by their original names
 * ({@code import {a as b}} makes {@code b()} a call of {@code a}).
 */
abstract class TreeSitterEcmaSupport extends TreeSitterCallSupport {

    private static final Set<String> FUNCTIONS = Set.of(
            "function_declaration", "generator_function_declaration", "method_definition");

    private static final Set<String> DECISIONS = Set.of(
            "if_statement", "for_statement", "for_in_statement", "while_statement", "do_statement",
            "switch_case", "catch_clause", "ternary_expression", "&&", "||", "??");

    /** Values that make a variable or class field a function. */
    private static final Set<String> FUNCTION_VALUES = Set.of("arrow_function", "function_expression", "function");

    /** Nodes that can hold a function value under a name. */
    private static final Set<String> NAME_HOLDERS = Set.of(
            "variable_declarator", "field_definition", "public_field_definition");

    private static final Set<String> NAME_TYPES = Set.of(
            "identifier", "type_identifier", "property_identifier", "private_property_identifier");

    @Override
    protected Set<String> functionTypes() { return FUNCTIONS; }

    @Override
    protected Set<String> decisionTypes() { return DECISIONS; }

    @Override
    protected boolean isFunction(TSNode node, String type, Source src) {
        return FUNCTIONS.contains(type) || (NAME_HOLDERS.contains(type) && hasFunctionValue(node));
    }

    @Override
    protected void visit(TSNode node, String type, Source src, Collected out) {
        switch (type) {
            // import x from 'a' / import 'a' / export * from 'a'. A plain "export const x" has no string child.
            case "import_statement", "export_statement" -> addImport(child(node, "string"), src, out);
            case "call_expression" -> {
                TSNode callee = node.getChild(0);
                TSNode args = child(node, "arguments");
                if (callee == null || args == null) return;
                boolean require = "identifier".equals(callee.getType()) && "require".equals(src.text(callee));
                boolean dynamicImport = "import".equals(callee.getType());
                if (require || dynamicImport) addImport(child(args, "string"), src, out);
            }
            case "class_declaration", "abstract_class_declaration" -> addSymbol(node, "CLASS", src, out);
            case "interface_declaration", "type_alias_declaration" -> addSymbol(node, "INTERFACE", src, out);
            case "enum_declaration" -> addSymbol(node, "ENUM", src, out);
            case "function_declaration", "generator_function_declaration" -> addSymbol(node, "METHOD", src, out);
            case "method_definition" -> addSymbol(node, "METHOD", src, out);
            case "variable_declarator", "field_definition", "public_field_definition" -> {
                if (hasFunctionValue(node)) addSymbol(node, "METHOD", src, out);
            }
            default -> { }
        }
    }

    private static boolean hasFunctionValue(TSNode node) {
        for (int i = 0; i < node.getChildCount(); i++) {
            if (FUNCTION_VALUES.contains(node.getChild(i).getType())) return true;
        }
        return false;
    }

    /** The specifier is the text of a string node without its quotes. Duplicates are reported once. */
    private static void addImport(TSNode string, Source src, Collected out) {
        if (string == null) return;
        String text = src.text(string);
        if (text.length() < 3) return; // "" or ''
        String specifier = text.substring(1, text.length() - 1);
        if (out.imports.stream().noneMatch(i -> i.name().equals(specifier))) {
            out.imports.add(new RawImport(specifier, false, false));
        }
    }

    /** The symbol is named by the node's first direct child that is an identifier; none means skip it. */
    private static void addSymbol(TSNode node, String kind, Source src, Collected out) {
        for (int i = 0; i < node.getChildCount(); i++) {
            TSNode c = node.getChild(i);
            if (!NAME_TYPES.contains(c.getType())) continue;
            String name = src.text(c);
            String k = "METHOD".equals(kind) && "method_definition".equals(node.getType())
                    && "constructor".equals(name) ? "CONSTRUCTOR" : kind;
            out.symbols.add(new Symbol(name, k, src.line(c)));
            return;
        }
    }

    // ---- call graph facts ----

    private static final Set<String> FUNCTION_VALUE_TYPES = Set.of(
            "arrow_function", "function_expression", "function", "generator_function");
    private static final Set<String> TYPE_OPENERS = Set.of(
            "class_declaration", "abstract_class_declaration", "class", "interface_declaration");
    private static final Set<String> METHOD_NAMES = Set.of("property_identifier", "private_property_identifier");
    private static final Set<String> PARAMETERS = Set.of(
            "identifier", "required_parameter", "optional_parameter", "assignment_pattern", "object_pattern",
            "array_pattern");
    private static final Set<String> NOT_ARGUMENTS = Set.of("(", ")", ",", "comment");
    private static final String SUPER = "super";

    @Override
    protected boolean opensType(String nodeType) { return TYPE_OPENERS.contains(nodeType); }

    @Override
    protected String typeName(TSNode node, Source src) {
        TSNode name = childOf(node, Set.of("identifier", "type_identifier"));
        return name == null ? null : src.text(name); // an unnamed class expression has no name
    }

    @Override
    protected Callable openCallable(TSNode node, String type, TypeCtx types, boolean nested, FileCtx file,
                                    Source src) {
        String here = nested || types == null ? "" : types.name();
        switch (type) {
            case "function_declaration", "generator_function_declaration" -> {
                TSNode name = child(node, "identifier");
                return name == null ? null : callable(node, "", src.text(name), child(node, "formal_parameters"), types, src);
            }
            case "method_definition", "method_signature", "abstract_method_signature" -> {
                TSNode name = childOf(node, METHOD_NAMES);
                if (name == null) return null; // a computed name
                String n = src.text(name);
                if (type.equals("method_definition") && n.equals("constructor") && !here.isEmpty()) n = here;
                return callable(node, here, n, child(node, "formal_parameters"), types, src);
            }
            case "variable_declarator" -> {
                TSNode value = functionValue(node);
                TSNode name = node.getChildCount() > 0 && node.getChild(0).getType().equals("identifier") ? node.getChild(0) : null;
                return value == null || name == null ? null : callable(node, "", src.text(name), parametersOf(value), types, src);
            }
            case "field_definition", "public_field_definition" -> {
                TSNode value = functionValue(node);
                TSNode name = childOf(node, METHOD_NAMES);
                return value == null || name == null ? null : callable(node, here, src.text(name), parametersOf(value), types, src);
            }
            case "pair" -> {
                TSNode value = functionValue(node);
                TSNode key = child(node, "property_identifier");
                return value == null || key == null ? null : callable(node, "", src.text(key), parametersOf(value), types, src);
            }
            case "assignment_expression" -> {
                TSNode value = functionValue(node);
                if (value == null || node.getChildCount() < 3 || !node.getChild(0).getType().equals("member_expression")) return null;
                TSNode left = node.getChild(0);
                TSNode prop = left.getChild(left.getChildCount() - 1);
                if (!METHOD_NAMES.contains(prop.getType())) return null;
                String owner = assignedOwner(left.getChild(0), here, src);
                return callable(node, owner, src.text(prop), parametersOf(value), types, src);
            }
            default -> { return null; }
        }
    }

    /** {@code this.f = ...} belongs to the class, {@code Foo.prototype.f = ...} to Foo, {@code exports.f = ...} to nobody. */
    private static String assignedOwner(TSNode object, String here, Source src) {
        if (object.getType().equals("this")) return here;
        if (object.getType().equals("member_expression") && object.getChildCount() >= 3
                && src.text(object.getChild(object.getChildCount() - 1)).equals("prototype")
                && object.getChild(0).getType().equals("identifier")) {
            return src.text(object.getChild(0));
        }
        return "";
    }

    private static Callable callable(TSNode node, String owner, String name, TSNode params, TypeCtx types, Source src) {
        return new Callable(node, owner, name, parameterCount(params), src.line(node), types);
    }

    /** The function a declarator, field, pair or assignment holds, or null. */
    private static TSNode functionValue(TSNode node) {
        return childOf(node, FUNCTION_VALUE_TYPES);
    }

    /** The parameter list of a function value; an arrow with one bare parameter ({@code x => x}) has just the name. */
    private static TSNode parametersOf(TSNode fn) {
        TSNode list = child(fn, "formal_parameters");
        return list != null ? list : child(fn, "identifier");
    }

    private static int parameterCount(TSNode params) {
        if (params == null) return 0;
        if (params.getType().equals("identifier")) return 1;
        int n = 0;
        for (int i = 0; i < params.getChildCount(); i++) {
            TSNode p = params.getChild(i);
            if (!PARAMETERS.contains(p.getType())) continue;
            if (child(p, "this") != null) continue; // TypeScript's fake "this" parameter
            n++;
        }
        return n;
    }

    @Override
    protected void callsOf(TSNode node, String type, Callable caller, FileCtx file, Source src, List<CallSite> into) {
        if (type.equals("new_expression")) {
            TSNode ctor = after(node, "new");
            String name = ctor == null ? null : constructedName(ctor, file, src);
            TSNode args = child(node, "arguments");
            if (name != null) {
                into.add(new CallSite(caller.type, caller.name, caller.arity, name, args == null ? 0 : argumentCount(args), name));
            }
            return;
        }
        if (!type.equals("call_expression") || node.getChildCount() < 2) return;
        TSNode fn = node.getChild(0);
        TSNode args = child(node, "arguments");
        if (args == null) return; // a tagged template
        int arity = argumentCount(args);
        if (fn.getType().equals("identifier")) {
            String name = src.text(fn);
            if (name.equals("require")) return;
            into.add(new CallSite(caller.type, caller.name, caller.arity, file.aliases.getOrDefault(name, name), arity, ""));
        } else if (fn.getType().equals("member_expression") && fn.getChildCount() >= 3) {
            TSNode prop = fn.getChild(fn.getChildCount() - 1);
            if (!METHOD_NAMES.contains(prop.getType())) return;
            String receiver = receiverOf(fn.getChild(0), caller, file, src);
            if (SUPER.equals(receiver)) return;
            into.add(new CallSite(caller.type, caller.name, caller.arity, src.text(prop), arity, receiver));
        }
    }

    private static int argumentCount(TSNode args) {
        return countExcept(args, NOT_ARGUMENTS);
    }

    /** The class named after {@code new}: {@code Foo}, {@code ns.Foo}, or an imported alias of one. */
    private static String constructedName(TSNode ctor, FileCtx file, Source src) {
        if (ctor.getType().equals("identifier")) return file.aliases.getOrDefault(src.text(ctor), src.text(ctor));
        if (ctor.getType().equals("member_expression")) {
            TSNode prop = ctor.getChild(ctor.getChildCount() - 1);
            return METHOD_NAMES.contains(prop.getType()) ? src.text(prop) : null;
        }
        return null;
    }

    /** "" for this, SUPER for super, a class or module name where it can be told cheaply, otherwise null. */
    private String receiverOf(TSNode obj, Callable caller, FileCtx file, Source src) {
        switch (obj.getType()) {
            case "this" -> { return ""; }
            case "super" -> { return SUPER; }
            case "identifier" -> {
                String id = src.text(obj);
                String t = scope(caller, file, src).locals().get(id);
                if (t != null) return t;
                String alias = file.aliases.get(id);
                if (alias != null) return alias;
                return isUpper(id) ? id : null; // Util.f(): a class
            }
            case "member_expression" -> {
                TSNode root = obj.getChild(0);
                TSNode prop = obj.getChild(obj.getChildCount() - 1);
                if (!METHOD_NAMES.contains(prop.getType())) return null;
                String name = src.text(prop);
                if (root.getType().equals("this")) return scope(caller, file, src).fields().get(name); // this.repo.f()
                TSNode base = root;
                while (base.getType().equals("member_expression") && base.getChildCount() > 0) base = base.getChild(0);
                if (base.getType().equals("identifier") && file.aliases.containsKey(src.text(base))) return name; // ns.sub.f()
                return isUpper(name) && isUpper(src.text(base)) ? name : null; // Outer.Inner.f()
            }
            case "non_null_expression" -> { return obj.getChildCount() == 0 ? null : receiverOf(obj.getChild(0), caller, file, src); }
            case "parenthesized_expression" -> { return obj.getChildCount() < 3 ? null : receiverOf(obj.getChild(1), caller, file, src); }
            case "as_expression" -> { return obj.getChildCount() < 3 ? null : annotation(obj.getChild(obj.getChildCount() - 1), src); }
            case "new_expression" -> {
                TSNode ctor = after(obj, "new");
                return ctor == null ? null : constructedName(ctor, file, src);
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
                case "import_statement" -> addImportAliases(n, src, file);
                case "variable_declarator" -> addRequireAliases(n, src, file);
                default -> { }
            }
        });
        return file;
    }

    /** {@code import d, * as ns, {a as b} from './m'}. The module's own name stands for a default or namespace import. */
    private static void addImportAliases(TSNode n, Source src, FileCtx file) {
        TSNode clause = child(n, "import_clause");
        TSNode source = child(n, "string");
        if (clause == null || source == null) return;
        String module = moduleName(src.text(source));
        for (int i = 0; i < clause.getChildCount(); i++) {
            TSNode c = clause.getChild(i);
            switch (c.getType()) {
                case "identifier" -> file.aliases.put(src.text(c), module);
                case "namespace_import" -> {
                    TSNode id = child(c, "identifier");
                    if (id != null) file.aliases.put(src.text(id), module);
                }
                case "named_imports" -> {
                    for (int j = 0; j < c.getChildCount(); j++) {
                        TSNode spec = c.getChild(j);
                        if (!spec.getType().equals("import_specifier")) continue;
                        TSNode name = child(spec, "identifier");
                        TSNode alias = lastChild(spec, "identifier");
                        if (name != null && alias != null) file.aliases.put(src.text(alias), src.text(name));
                    }
                }
                default -> { }
            }
        }
    }

    /** {@code const ns = require('./ns')} and {@code const {a, b: c} = require('./m')}. */
    private static void addRequireAliases(TSNode n, Source src, FileCtx file) {
        TSNode value = after(n, "=");
        if (value == null || !value.getType().equals("call_expression") || value.getChildCount() < 2) return;
        TSNode fn = value.getChild(0);
        TSNode args = child(value, "arguments");
        TSNode source = args == null ? null : child(args, "string");
        if (source == null || !fn.getType().equals("identifier") || !src.text(fn).equals("require")) return;
        TSNode target = n.getChild(0);
        if (target.getType().equals("identifier")) {
            file.aliases.put(src.text(target), moduleName(src.text(source)));
        } else if (target.getType().equals("object_pattern")) {
            for (int i = 0; i < target.getChildCount(); i++) {
                TSNode p = target.getChild(i);
                if (p.getType().equals("shorthand_property_identifier_pattern")) {
                    file.aliases.put(src.text(p), src.text(p));
                } else if (p.getType().equals("pair_pattern") && p.getChildCount() >= 3
                        && p.getChild(p.getChildCount() - 1).getType().equals("identifier")) {
                    file.aliases.put(src.text(p.getChild(p.getChildCount() - 1)), src.text(p.getChild(0)));
                }
            }
        }
    }

    /** The name a module goes by: {@code './a/util.js'} is {@code util}, {@code './a/index'} is {@code a}. */
    static String moduleName(String quotedSpecifier) {
        String spec = quotedSpecifier.length() >= 2 ? quotedSpecifier.substring(1, quotedSpecifier.length() - 1) : quotedSpecifier;
        String[] parts = spec.split("/");
        String last = parts[parts.length - 1].replaceAll("\\.(m|c)?[jt]sx?$", "");
        if ((last.equals("index") || last.isEmpty()) && parts.length > 1) last = parts[parts.length - 2];
        return last.replaceAll("^\\.+$", "");
    }

    @Override
    protected Scope scopeOf(Callable c, FileCtx file, Source src) {
        Map<String, String> locals = new HashMap<>();
        addParameterTypes(parametersOf(functionOf(c.node)), locals, file, src);
        walk(c.node, n -> {
            if (n.getType().equals("variable_declarator") && n.getChildCount() > 0
                    && n.getChild(0).getType().equals("identifier")) {
                putIfKnown(locals, src.text(n.getChild(0)), declaredType(n, file, src));
            } else if (n.getType().equals("assignment_expression") && n.getChildCount() >= 3
                    && n.getChild(0).getType().equals("identifier")) {
                putIfKnown(locals, src.text(n.getChild(0)), constructedType(after(n, "="), file, src));
            }
        });
        Map<String, String> fields = new HashMap<>();
        for (TypeCtx t = c.types; t != null; t = t.parent()) {
            for (var e : fieldsOf(t, file, src).entrySet()) fields.putIfAbsent(e.getKey(), e.getValue());
        }
        return new Scope(locals, fields);
    }

    /** The function node whose parameters a callable has: itself for a method, the held function for a variable. */
    private static TSNode functionOf(TSNode callable) {
        TSNode held = functionValue(callable);
        return held != null ? held : callable;
    }

    /** {@code x: Foo} or {@code x = new Foo()}. */
    private static String declaredType(TSNode declarator, FileCtx file, Source src) {
        TSNode annotation = child(declarator, "type_annotation");
        if (annotation != null) return annotation(annotation, src);
        return constructedType(after(declarator, "="), file, src);
    }

    private static String constructedType(TSNode value, FileCtx file, Source src) {
        if (value == null || !value.getType().equals("new_expression")) return null;
        TSNode ctor = after(value, "new");
        return ctor == null ? null : constructedName(ctor, file, src);
    }

    /** TypeScript parameters carry their type; each is a local, and an accessibility modifier makes it a field too. */
    private static void addParameterTypes(TSNode params, Map<String, String> into, FileCtx file, Source src) {
        if (params == null || params.getType().equals("identifier")) return;
        for (int i = 0; i < params.getChildCount(); i++) {
            TSNode p = params.getChild(i);
            if (!p.getType().equals("required_parameter") && !p.getType().equals("optional_parameter")) continue;
            TSNode name = child(p, "identifier");
            TSNode type = child(p, "type_annotation");
            if (name != null && type != null) putIfKnown(into, src.text(name), annotation(type, src));
        }
    }

    /** Typed or constructed fields of a class, parameter properties, and {@code this.x = ...} in its methods. */
    private Map<String, String> fieldsOf(TypeCtx t, FileCtx file, Source src) {
        return file.fieldsByType.computeIfAbsent(t.node().getStartByte(), k -> {
            Map<String, String> fields = new HashMap<>();
            TSNode body = childOf(t.node(), Set.of("class_body", "interface_body", "object_type"));
            if (body == null) return fields;
            for (int i = 0; i < body.getChildCount(); i++) {
                TSNode m = body.getChild(i);
                switch (m.getType()) {
                    case "field_definition", "public_field_definition", "property_signature" -> {
                        TSNode name = childOf(m, METHOD_NAMES);
                        if (name != null) putIfKnown(fields, src.text(name), declaredType(m, file, src));
                    }
                    case "method_definition" -> {
                        TSNode params = child(m, "formal_parameters");
                        TSNode ctor = childOf(m, METHOD_NAMES);
                        if (params != null && ctor != null && src.text(ctor).equals("constructor")) parameterProperties(params, fields, src);
                        thisAssignments(m, params, fields, file, src);
                    }
                    default -> { }
                }
            }
            return fields;
        });
    }

    /** {@code constructor(private repo: Repo)} declares the field {@code repo}. */
    private static void parameterProperties(TSNode params, Map<String, String> fields, Source src) {
        for (int i = 0; i < params.getChildCount(); i++) {
            TSNode p = params.getChild(i);
            boolean property = child(p, "accessibility_modifier") != null || child(p, "readonly") != null;
            TSNode name = child(p, "identifier");
            TSNode type = child(p, "type_annotation");
            if (property && name != null && type != null) putIfKnown(fields, src.text(name), annotation(type, src));
        }
    }

    /** {@code this.x = new Foo()} and {@code this.x = param} for a typed parameter. */
    private static void thisAssignments(TSNode method, TSNode params, Map<String, String> fields, FileCtx file, Source src) {
        Map<String, String> typed = new HashMap<>();
        addParameterTypes(params, typed, file, src);
        walk(method, n -> {
            if (!n.getType().equals("assignment_expression") || n.getChildCount() < 3) return;
            TSNode left = n.getChild(0);
            if (!left.getType().equals("member_expression") || !left.getChild(0).getType().equals("this")) return;
            TSNode prop = left.getChild(left.getChildCount() - 1);
            if (!METHOD_NAMES.contains(prop.getType())) return;
            TSNode value = after(n, "=");
            String type = constructedType(value, file, src);
            if (type == null && value != null && value.getType().equals("identifier")) type = typed.get(src.text(value));
            putIfKnown(fields, src.text(prop), type);
        });
    }

    /** The class a TypeScript type names: {@code Foo}, {@code a.Foo}, {@code Foo<T>}, {@code Foo | null}; null for the rest. */
    private static String annotation(TSNode t, Source src) {
        if (t == null) return null;
        switch (t.getType()) {
            case "type_annotation" -> { return t.getChildCount() < 2 ? null : annotation(t.getChild(1), src); }
            case "type_identifier" -> { return src.text(t); }
            case "generic_type" -> { return t.getChildCount() == 0 ? null : annotation(t.getChild(0), src); }
            case "nested_type_identifier" -> {
                TSNode last = lastChild(t, "type_identifier");
                return last == null ? null : src.text(last);
            }
            case "parenthesized_type" -> { return t.getChildCount() < 3 ? null : annotation(t.getChild(1), src); }
            case "union_type" -> {
                for (int i = 0; i < t.getChildCount(); i++) {
                    String a = annotation(t.getChild(i), src);
                    if (a != null) return a;
                }
                return null;
            }
            default -> { return null; }
        }
    }
}
