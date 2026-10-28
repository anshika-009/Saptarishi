package com.saptarishi.backend.parse;

import com.saptarishi.backend.parse.ParsedFile.RawImport;
import com.saptarishi.backend.parse.ParsedFile.Symbol;
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
 */
@Component
public class PythonLanguageSupport extends TreeSitterSupport {

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
}