package com.saptarishi.backend.parse;

import com.saptarishi.backend.parse.ParsedFile.RawImport;
import com.saptarishi.backend.parse.ParsedFile.Symbol;
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
 */
abstract class TreeSitterEcmaSupport extends TreeSitterSupport {

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
}