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
import org.treesitter.TreeSitterRust;

/**
 * Rust. Imports are module paths: every {@code use} is expanded to one path per item
 * ({@code use a::{b, c::d};} gives {@code a::b} and {@code a::c::d}; {@code as} and {@code *} are dropped),
 * and {@code mod x;} (a module in its own file) is reported as {@code self::x}. Turning a path into a file
 * follows the crate layout and is done by {@code RustImportResolver}.
 *
 * <p>Complexity: one per function and closure, plus one per {@code if} (including {@code if let}),
 * {@code for}, {@code while}, {@code loop}, match arm other than {@code _}, {@code &&} and {@code ||}.
 *
 * <p>Call graph facts (see {@link TreeSitterCallSupport}): a function in an {@code impl} block is owned by the
 * type the block is for ({@code impl Trait for Foo} gives {@code Foo}), a trait's functions (with or without
 * a default body) by the trait, any other function by nobody. The receiver of a call is {@code ""} for
 * {@code self.f()}, {@code Self::f()}, {@code f()} and {@code crate::f()}; the type for {@code Foo::f()},
 * for a variable of that type ({@code x: Foo}, {@code let x = Foo::new()}, {@code Foo {..}}, a parameter),
 * and for {@code self.field.f()} from the struct declared in the same file; the module for
 * {@code util::f()} and {@code crate::a::util::f()}. {@code Box}, {@code Rc}, {@code Arc} and the like are
 * looked through ({@code Arc<Foo>} is a {@code Foo}); macros are not calls.
 */
@Component
public class RustLanguageSupport extends TreeSitterCallSupport {

    private static final Set<String> FUNCTIONS = Set.of("function_item", "closure_expression");
    private static final Set<String> DECISIONS = Set.of(
            "if_expression", "for_expression", "while_expression", "loop_expression", "&&", "||");

    @Override
    public String language() { return "rust"; }

    @Override
    protected TSLanguage grammar() { return new TreeSitterRust(); }

    @Override
    protected Set<String> functionTypes() { return FUNCTIONS; }

    @Override
    protected Set<String> decisionTypes() { return DECISIONS; }

    @Override
    protected boolean isDecision(TSNode node, String type, Source src) {
        if (type.equals("match_arm")) {
            TSNode pattern = child(node, "match_pattern");
            return pattern == null || !src.text(pattern).trim().equals("_");
        }
        return super.isDecision(node, type, src);
    }

    @Override
    protected void visit(TSNode node, String type, Source src, Collected out) {
        switch (type) {
            case "use_declaration" -> {
                for (String path : expandUse(src.text(node))) out.imports.add(new RawImport(path, false, false));
            }
            case "mod_item" -> {
                // "mod x;" lives in another file; "mod x { ... }" is inline and needs no file
                if (child(node, "declaration_list") != null) return;
                TSNode name = child(node, "identifier");
                if (name != null) out.imports.add(new RawImport("self::" + src.text(name), false, false));
            }
            case "struct_item" -> addSymbol(node, "type_identifier", "CLASS", src, out);
            case "enum_item" -> addSymbol(node, "type_identifier", "ENUM", src, out);
            case "trait_item" -> addSymbol(node, "type_identifier", "INTERFACE", src, out);
            case "function_item" -> addSymbol(node, "identifier", "METHOD", src, out);
            default -> { }
        }
    }

    private static void addSymbol(TSNode node, String nameType, String kind, Source src, Collected out) {
        TSNode name = child(node, nameType);
        if (name != null) out.symbols.add(new Symbol(src.text(name), kind, src.line(name)));
    }

    /** {@code pub use a::{b, c::{d, e as f}, g::*};} to [a::b, a::c::d, a::c::e, a::g]. */
    static List<String> expandUse(String declaration) {
        String s = declaration.replaceAll("(?s)/\\*.*?\\*/", " ").replaceAll("//[^\\n]*", " ").trim();
        s = s.replaceFirst("^pub\\s*(\\([^)]*\\))?\\s*", "").replaceFirst("^use\\s+", "");
        if (s.endsWith(";")) s = s.substring(0, s.length() - 1);
        List<String> out = new ArrayList<>();
        expand(s.replaceAll("\\s+", " ").trim(), "", out);
        return out;
    }

    private static void expand(String s, String prefix, List<String> out) {
        int open = s.indexOf('{');
        if (open >= 0) {
            int depth = 0;
            int close = -1;
            for (int i = open; i < s.length(); i++) {
                if (s.charAt(i) == '{') depth++;
                else if (s.charAt(i) == '}' && --depth == 0) { close = i; break; }
            }
            if (close < 0) return;
            String base = prefix + s.substring(0, open).trim();
            if (!base.isEmpty() && !base.endsWith("::")) base += "::";
            depth = 0;
            int start = open + 1;
            for (int i = open + 1; i <= close; i++) {
                char c = s.charAt(i);
                if (c == '{') depth++;
                else if (c == '}' && i < close) depth--;
                if ((c == ',' && depth == 0) || i == close) {
                    String part = s.substring(start, i).trim();
                    if (!part.isEmpty()) expand(part, base, out);
                    start = i + 1;
                }
            }
            return;
        }
        String leaf = s.replaceFirst("\\s+as\\s+\\w+$", "").trim();
        String path = prefix + leaf;
        if (path.endsWith("::*")) path = path.substring(0, path.length() - 3);
        if (path.endsWith("::self")) path = path.substring(0, path.length() - 6);
        path = path.replaceAll("\\s+", "");
        if (!path.isEmpty() && !path.equals("*") && !path.equals("self")) out.add(path);
    }

    // ---- call graph facts ----

    private static final Set<String> TYPE_NODES = Set.of(
            "type_identifier", "generic_type", "scoped_type_identifier", "reference_type", "dynamic_type",
            "abstract_type", "tuple_type", "array_type");
    private static final Set<String> WRAPPERS = Set.of("Box", "Rc", "Arc", "Cell", "RefCell", "Mutex", "RwLock", "Option");
    private static final Set<String> NOT_ARGUMENTS = Set.of("(", ")", ",", "comment");
    private static final Set<String> BUILDERS = Set.of("new", "default", "from", "create", "open");

    /** The struct fields declared in this file: struct name to field name to the type of the field. */
    private static final class RustCtx extends FileCtx {
        final Map<String, Map<String, String>> structFields = new HashMap<>();
    }

    @Override
    protected boolean opensType(String nodeType) { return nodeType.equals("impl_item") || nodeType.equals("trait_item"); }

    @Override
    protected String typeName(TSNode node, Source src) {
        if (node.getType().equals("trait_item")) {
            TSNode name = child(node, "type_identifier");
            return name == null ? null : src.text(name);
        }
        TSNode target = null; // the type being implemented is the last type before the body
        for (int i = 0; i < node.getChildCount(); i++) {
            TSNode c = node.getChild(i);
            if (c.getType().equals("declaration_list")) break;
            if (TYPE_NODES.contains(c.getType())) target = c;
        }
        return simpleType(target, src);
    }

    @Override
    protected Callable openCallable(TSNode node, String type, TypeCtx types, boolean nested, FileCtx file, Source src) {
        if (!type.equals("function_item") && !type.equals("function_signature_item")) return null;
        TSNode name = child(node, "identifier");
        if (name == null) return null;
        String owner = nested || types == null ? "" : types.name();
        return new Callable(node, owner, src.text(name), parameterCount(child(node, "parameters")), src.line(node), types);
    }

    /** Parameters other than {@code self}. */
    private static int parameterCount(TSNode params) {
        if (params == null) return 0;
        int n = 0;
        for (int i = 0; i < params.getChildCount(); i++) if (params.getChild(i).getType().equals("parameter")) n++;
        return n;
    }

    @Override
    protected void callsOf(TSNode node, String type, Callable caller, FileCtx file, Source src, List<CallSite> into) {
        if (!type.equals("call_expression") || node.getChildCount() < 2) return;
        TSNode fn = node.getChild(0);
        if (fn.getType().equals("generic_function") && fn.getChildCount() > 0) fn = fn.getChild(0); // f::<T>(x)
        TSNode args = child(node, "arguments");
        if (args == null) return;
        int arity = countExcept(args, NOT_ARGUMENTS);
        switch (fn.getType()) {
            case "identifier" -> {
                String name = src.text(fn);
                into.add(new CallSite(caller.type, caller.name, caller.arity, file.aliases.getOrDefault(name, name), arity, ""));
            }
            case "scoped_identifier" -> {
                TSNode name = fn.getChild(fn.getChildCount() - 1);
                TSNode path = fn.getChildCount() >= 3 ? fn.getChild(0) : null;
                if (!name.getType().equals("identifier") || path == null) return;
                into.add(new CallSite(caller.type, caller.name, caller.arity, src.text(name), arity, pathReceiver(path, file, src)));
            }
            case "field_expression" -> {
                TSNode field = fn.getChild(fn.getChildCount() - 1);
                if (!field.getType().equals("field_identifier") || fn.getChildCount() < 3) return;
                into.add(new CallSite(caller.type, caller.name, caller.arity, src.text(field), arity,
                        valueReceiver(fn.getChild(0), caller, (RustCtx) file, src)));
            }
            default -> { }
        }
    }

    /** {@code Self::f()} and {@code crate::f()} are the caller's own; {@code Foo::f()} a type; {@code util::f()} a module. */
    private static String pathReceiver(TSNode path, FileCtx file, Source src) {
        switch (path.getType()) {
            case "self", "crate", "super", "metavariable" -> { return ""; }
            case "identifier" -> {
                String id = src.text(path);
                if (id.equals("Self")) return "";
                return file.aliases.getOrDefault(id, id);
            }
            case "scoped_identifier" -> { // crate::a::util: the module is the last name of the path
                TSNode last = path.getChild(path.getChildCount() - 1);
                return last.getType().equals("identifier") ? file.aliases.getOrDefault(src.text(last), src.text(last)) : null;
            }
            default -> { return null; } // <T as Trait>::f(), Vec::<T>::f()
        }
    }

    /** The receiver of {@code value.f()}. */
    private String valueReceiver(TSNode value, Callable caller, RustCtx file, Source src) {
        switch (value.getType()) {
            case "self" -> { return ""; }
            case "identifier" -> { return scope(caller, file, src).locals().get(src.text(value)); }
            case "field_expression" -> { // self.repo.f()
                TSNode root = value.getChild(0);
                TSNode field = value.getChild(value.getChildCount() - 1);
                if (!root.getType().equals("self") || !field.getType().equals("field_identifier")) return null;
                Map<String, String> fields = file.structFields.get(caller.type);
                return fields == null ? null : fields.get(src.text(field));
            }
            case "parenthesized_expression" -> { return value.getChildCount() < 3 ? null : valueReceiver(value.getChild(1), caller, file, src); }
            default -> { return null; }
        }
    }

    /**
     * {@code Foo}, {@code &mut Foo}, {@code a::Foo}, {@code Arc<Foo>}, {@code dyn Foo}; null for primitives, tuples and
     * arrays.
     */
    private static String simpleType(TSNode t, Source src) {
        if (t == null) return null;
        switch (t.getType()) {
            case "type_identifier" -> { return src.text(t); }
            case "scoped_type_identifier" -> {
                TSNode last = lastChild(t, "type_identifier");
                return last == null ? null : src.text(last);
            }
            case "reference_type" -> { return t.getChildCount() == 0 ? null : simpleType(t.getChild(t.getChildCount() - 1), src); }
            case "dynamic_type", "abstract_type" -> { return t.getChildCount() == 0 ? null : simpleType(t.getChild(t.getChildCount() - 1), src); }
            case "generic_type" -> {
                String base = simpleType(child(t, "type_identifier") != null ? child(t, "type_identifier") : child(t, "scoped_type_identifier"), src);
                TSNode args = child(t, "type_arguments");
                if (base != null && WRAPPERS.contains(base) && args != null) {
                    for (int i = 0; i < args.getChildCount(); i++) {
                        String inner = TYPE_NODES.contains(args.getChild(i).getType()) ? simpleType(args.getChild(i), src) : null;
                        if (inner != null) return inner;
                    }
                    return null;
                }
                return base;
            }
            default -> { return null; }
        }
    }

    // ---- what names stand for ----

    @Override
    protected FileCtx beginFile(TSNode root, Source src) {
        RustCtx file = new RustCtx();
        walk(root, n -> {
            switch (n.getType()) {
                case "use_as_clause" -> { // use a::b as c
                    TSNode path = n.getChildCount() == 0 ? null : n.getChild(0);
                    TSNode alias = lastChild(n, "identifier");
                    if (path == null || alias == null) return;
                    TSNode last = path.getType().equals("identifier") ? path : lastChild(path, "identifier");
                    if (last != null) file.aliases.put(src.text(alias), src.text(last));
                }
                case "struct_item" -> {
                    TSNode name = child(n, "type_identifier");
                    TSNode body = child(n, "field_declaration_list");
                    if (name == null || body == null) return;
                    Map<String, String> fields = new HashMap<>();
                    for (int i = 0; i < body.getChildCount(); i++) {
                        TSNode f = body.getChild(i);
                        if (!f.getType().equals("field_declaration")) continue;
                        TSNode fname = child(f, "field_identifier");
                        TSNode ftype = f.getChild(f.getChildCount() - 1);
                        if (fname != null && !ftype.equals(fname)) putIfKnown(fields, src.text(fname), simpleType(ftype, src));
                    }
                    file.structFields.put(src.text(name), fields);
                }
                default -> { }
            }
        });
        return file;
    }

    @Override
    protected Scope scopeOf(Callable c, FileCtx file, Source src) {
        Map<String, String> locals = new HashMap<>();
        TSNode params = child(c.node, "parameters");
        if (params != null) {
            for (int i = 0; i < params.getChildCount(); i++) {
                TSNode p = params.getChild(i);
                if (!p.getType().equals("parameter")) continue;
                TSNode name = child(p, "identifier");
                TSNode type = p.getChild(p.getChildCount() - 1);
                if (name != null && name != type) putIfKnown(locals, src.text(name), selfAs(simpleType(type, src), c));
            }
        }
        walk(c.node, n -> {
            if (!n.getType().equals("let_declaration") || n.getChildCount() < 2) return;
            TSNode pattern = n.getChild(1).getType().equals("mutable_specifier") && n.getChildCount() > 2 ? n.getChild(2) : n.getChild(1);
            if (!pattern.getType().equals("identifier")) return;
            TSNode annotation = null;
            TSNode value = after(n, "=");
            for (int i = 0; i + 1 < n.getChildCount(); i++) {
                if (n.getChild(i).getType().equals(":")) annotation = n.getChild(i + 1);
            }
            String type = annotation != null ? simpleType(annotation, src) : valueType(value, src);
            putIfKnown(locals, src.text(pattern), selfAs(type, c));
        });
        return new Scope(locals, Map.of());
    }

    private static String selfAs(String type, Callable c) {
        return "Self".equals(type) ? (c.type.isEmpty() ? null : c.type) : type;
    }

    /** {@code Foo {..}} and {@code Foo::new(..)} (and default, from, create, open, with_*) are Foos. */
    private static String valueType(TSNode value, Source src) {
        if (value == null) return null;
        if (value.getType().equals("struct_expression")) return simpleType(child(value, "type_identifier"), src);
        if (!value.getType().equals("call_expression") || value.getChildCount() == 0) return null;
        TSNode fn = value.getChild(0);
        if (!fn.getType().equals("scoped_identifier") || fn.getChildCount() < 3) return null;
        TSNode path = fn.getChild(0);
        TSNode name = fn.getChild(fn.getChildCount() - 1);
        if (!path.getType().equals("identifier") || !name.getType().equals("identifier")) return null;
        String fnName = src.text(name);
        boolean builds = BUILDERS.contains(fnName) || fnName.startsWith("with_") || fnName.startsWith("new_");
        String type = src.text(path);
        return builds && isUpper(type) && !WRAPPERS.contains(type) ? type : null;
    }
}
