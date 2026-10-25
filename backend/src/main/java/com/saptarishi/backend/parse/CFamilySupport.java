package com.saptarishi.backend.parse;

import com.saptarishi.backend.parse.ParsedFile.RawImport;
import com.saptarishi.backend.parse.ParsedFile.Symbol;
import java.util.Set;
import org.treesitter.TSNode;

/**
 * What C and C++ have in common: the preprocessor, the way functions and types are declared, and what
 * counts as a decision point. {@link CLanguageSupport} and {@link CppLanguageSupport} only pick the grammar
 * and add the few node types that exist in C++ alone.
 *
 * <p>Imports are the {@code #include} directives, kept exactly as written including the delimiters
 * ({@code "util.h"} or {@code <sys/types.h>}), because the two forms are searched differently. Turning an
 * include into a file needs the list of files in the repository and is done by {@code CIncludeResolver}.
 * Includes that are not a literal ({@code #include MACRO_HEADER}) cannot be read and are skipped. Includes
 * inside {@code #if} blocks and {@code extern "C"} are read like any other.
 *
 * <p>Symbols: structs, unions and C++ classes that have a body are CLASS, enums with a body are ENUM, and
 * function definitions are METHOD (a prototype alone is not a symbol). An anonymous struct or enum that is
 * given a name by {@code typedef} gets the typedef's name. For {@code A::m} and {@code ns::A::m} the symbol is
 * the last part, {@code m}.
 *
 * <p>Complexity follows the Java definition: one per function, plus one per {@code if}, {@code for},
 * {@code while}, {@code do}, {@code case} (a {@code default} is not counted), {@code ?:}, {@code &&} and
 * {@code ||}.
 */
abstract class CFamilySupport extends TreeSitterSupport {

    static final Set<String> FUNCTIONS = Set.of("function_definition");
    static final Set<String> DECISIONS = Set.of(
            "if_statement", "for_statement", "while_statement", "do_statement",
            "conditional_expression", "&&", "||");

    private static final Set<String> TYPE_SPECIFIERS = Set.of(
            "struct_specifier", "union_specifier", "class_specifier", "enum_specifier");

    @Override
    protected boolean isDecision(TSNode node, String type, Source src) {
        if (type.equals("case_statement")) {
            // "case X:" counts, "default:" does not
            return node.getChildCount() > 0 && "case".equals(node.getChild(0).getType());
        }
        return super.isDecision(node, type, src);
    }

    @Override
    protected void visit(TSNode node, String type, Source src, Collected out) {
        switch (type) {
            case "preproc_include" -> addInclude(node, src, out);
            case "struct_specifier", "union_specifier", "class_specifier" ->
                    addType(node, "CLASS", "field_declaration_list", src, out);
            case "enum_specifier" -> addType(node, "ENUM", "enumerator_list", src, out);
            case "type_definition" -> addTypedef(node, src, out);
            case "function_definition" -> addFunction(node, src, out);
            default -> { }
        }
    }

    private static void addInclude(TSNode node, Source src, Collected out) {
        for (int i = 0; i < node.getChildCount(); i++) {
            TSNode c = node.getChild(i);
            String t = c.getType();
            if (t.equals("string_literal") || t.equals("system_lib_string")) {
                String text = src.text(c);
                if (text.length() > 2) out.imports.add(new RawImport(text, false, false));
                return;
            }
        }
    }

    /** A struct, union, class or enum that has a body and a name. A bare {@code struct Foo x;} has no body. */
    private static void addType(TSNode node, String kind, String bodyType, Source src, Collected out) {
        if (child(node, bodyType) == null) return;
        TSNode name = child(node, "type_identifier");
        if (name != null) out.symbols.add(new Symbol(src.text(name), kind, src.line(name)));
    }

    /** {@code typedef struct { ... } Name;}. A named struct was already recorded under its own name. */
    private static void addTypedef(TSNode node, Source src, Collected out) {
        TSNode spec = null;
        TSNode name = null;
        for (int i = 0; i < node.getChildCount(); i++) {
            TSNode c = node.getChild(i);
            String t = c.getType();
            if (spec == null && TYPE_SPECIFIERS.contains(t)) spec = c;
            else if (t.equals("type_identifier")) name = c; // the last one is the new name
        }
        if (spec == null || name == null) return;
        boolean hasBody = child(spec, "field_declaration_list") != null || child(spec, "enumerator_list") != null;
        if (!hasBody || child(spec, "type_identifier") != null) return;
        String kind = spec.getType().equals("enum_specifier") ? "ENUM" : "CLASS";
        out.symbols.add(new Symbol(src.text(name), kind, src.line(name)));
    }

    private static void addFunction(TSNode node, Source src, Collected out) {
        TSNode name = functionName(node);
        if (name != null) out.symbols.add(new Symbol(src.text(name), "METHOD", src.line(name)));
    }

    /**
     * The declared name of a function definition. The declarator may be wrapped in pointer and reference
     * declarators ({@code char *dup(...)}, {@code int &ref()}); the name is the first child of the function
     * declarator, and for a qualified name the last part of it. Null when there is no plain name, such as
     * a function that returns a function pointer.
     */
    static TSNode functionName(TSNode definition) {
        TSNode d = declaratorChild(definition);
        for (int depth = 0; d != null && depth < 8; depth++) {
            if (d.getType().equals("function_declarator")) {
                return d.getChildCount() == 0 ? null : plainName(d.getChild(0));
            }
            d = declaratorChild(d);
        }
        return null;
    }

    private static TSNode declaratorChild(TSNode node) {
        for (int i = 0; i < node.getChildCount(); i++) {
            TSNode c = node.getChild(i);
            if (c.getType().endsWith("declarator")) return c;
        }
        return null;
    }

    private static TSNode plainName(TSNode n) {
        int guard = 0;
        while (n != null && n.getType().equals("qualified_identifier") && guard++ < 16) {
            n = n.getChildCount() == 0 ? null : n.getChild(n.getChildCount() - 1);
        }
        if (n == null) return null;
        return switch (n.getType()) {
            case "identifier", "field_identifier", "destructor_name", "operator_name" -> n;
            default -> null;
        };
    }
}
