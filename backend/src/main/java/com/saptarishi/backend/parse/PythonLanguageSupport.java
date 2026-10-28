package com.saptarishi.backend.parse;

import com.saptarishi.backend.parse.ParsedFile.RawImport;
import com.saptarishi.backend.parse.ParsedFile.Symbol;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Component;
import org.treesitter.TSLanguage;
import org.treesitter.TSNode;
import org.treesitter.TreeSitterPython;

/**
 * Python, read with the tree-sitter grammar.
 *
 * <p>Imports are reported as dotted module names. A relative import keeps its leading dots
 * ({@code from ..pkg import a} gives {@code ..pkg} and {@code ..pkg.a}), and {@code from pkg import name}
 * reports both {@code pkg} and {@code pkg.name}, because {@code name} may be a submodule; the graph
 * builder keeps whichever of them is a file in the repository. {@code from pkg import *} is one wildcard
 * import of {@code pkg}. An import inside a function or a {@code try} counts like any other.
 *
 * <p>Symbols: every {@code class} (CLASS) and every {@code def} or {@code async def} (METHOD), nested ones
 * included, on the line of the name.
 *
 * <p>Complexity follows the Java definition: one point per {@code def}, plus one per decision point
 * ({@code if}, {@code elif}, {@code for}, {@code while}, {@code except}, each {@code case}, a guard,
 * {@code and}, {@code or}). Conditional expressions and the {@code for} and {@code if} of a comprehension
 * count too. A string is one token and nothing inside it is read, f-string expressions included, so a
 * docstring that says "if this and that" adds nothing.
 *
 * <p>A file with a syntax error is still read as far as tree-sitter can, as with the other grammars.
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

    @Override
    protected boolean isAtomic(String type) { return type.equals("string"); }

    @Override
    protected void visit(TSNode node, String type, Source src, Collected out) {
        switch (type) {
            case "import_statement" -> addImports(node, src, out);
            case "import_from_statement" -> addFrom(node, null, src, out);
            case "future_import_statement" -> addFrom(node, "__future__", src, out);
            case "class_definition" -> addSymbol(node, "CLASS", src, out);
            case "function_definition" -> addSymbol(node, "METHOD", src, out);
            default -> { }
        }
    }

    private static void addSymbol(TSNode node, String kind, Source src, Collected out) {
        TSNode name = child(node, "identifier");
        if (name != null) out.symbols.add(new Symbol(src.text(name), kind, src.line(name)));
    }

    /** {@code import a.b, c as d}: the module names, without their aliases. */
    private static void addImports(TSNode node, Source src, Collected out) {
        for (int i = 0; i < node.getChildCount(); i++) {
            String name = importedName(node.getChild(i), src);
            if (name != null) add(out, name, false);
        }
    }

    /**
     * {@code from M import a, b as c, (d, e)} gives M, M.a, M.b, M.d and M.e. {@code module} is preset for
     * {@code from __future__}, which the grammar gives its own node without a module child.
     */
    private static void addFrom(TSNode node, String module, Source src, Collected out) {
        boolean afterImport = false;
        boolean wildcard = false;
        List<String> names = new ArrayList<>();
        for (int i = 0; i < node.getChildCount(); i++) {
            TSNode c = node.getChild(i);
            String t = c.getType();
            if (!afterImport) {
                if (t.equals("import")) afterImport = true;
                else if (module == null && (t.equals("dotted_name") || t.equals("relative_import"))) {
                    module = compact(src.text(c));
                }
            } else if (t.equals("wildcard_import")) {
                wildcard = true;
            } else {
                String name = importedName(c, src);
                if (name != null) names.add(name);
            }
        }
        if (module == null || module.isEmpty()) return;
        if (wildcard) {
            add(out, module, true);
            return;
        }
        if (names.isEmpty() && module.chars().allMatch(ch -> ch == '.')) return; // "from . import" with nothing
        add(out, module, false);
        for (String name : names) {
            if (!name.matches("\\w+")) continue;
            add(out, module.endsWith(".") ? module + name : module + "." + name, false);
        }
    }

    /** A dotted name, or the name an {@code as} alias points at; null for anything else (commas, brackets). */
    private static String importedName(TSNode node, Source src) {
        String t = node.getType();
        if (t.equals("dotted_name")) return compact(src.text(node));
        if (t.equals("aliased_import")) {
            TSNode name = child(node, "dotted_name");
            return name == null ? null : compact(src.text(name));
        }
        return null;
    }

    /** Each module once; a wildcard import of a module is separate from a plain import of it. */
    private static void add(Collected out, String name, boolean wildcard) {
        for (RawImport i : out.imports) {
            if (i.name().equals(name) && i.isWildcard() == wildcard) return;
        }
        out.imports.add(new RawImport(name, false, wildcard));
    }

    /** Drops whitespace that may sit between the dots of a name. */
    private static String compact(String s) {
        return s.replaceAll("\\s+", "");
    }
}