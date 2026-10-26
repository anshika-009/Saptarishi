package com.saptarishi.backend.parse;

import com.saptarishi.backend.parse.ParsedFile.RawImport;
import com.saptarishi.backend.parse.ParsedFile.Symbol;
import java.util.Set;
import org.springframework.stereotype.Component;
import org.treesitter.TSLanguage;
import org.treesitter.TSNode;
import org.treesitter.TreeSitterPhp;

/**
 * PHP. Two kinds of import:
 * <ul>
 *   <li>{@code use A\B\C;} (also {@code use A\B\{C, D as E};}) is reported as the dotted name
 *       {@code A.B.C}. {@code use function} and {@code use const} are skipped. The file's
 *       {@code namespace} is its package name, so the same resolver as C# links files by the classes they
 *       mention.</li>
 *   <li>{@code require}, {@code require_once}, {@code include}, {@code include_once} with a string
 *       (also {@code __DIR__ . '/x.php'}) is reported as {@code file:./x.php}, relative to the including
 *       file, and resolved by {@code FileRequireResolver}.</li>
 * </ul>
 * Classes, interfaces, traits and enums are symbols; functions and methods are METHOD. Complexity: one per
 * function, method and closure, plus one per {@code if}, {@code elseif}, loop, {@code case}, {@code catch},
 * the ternary {@code ?:}, {@code &&}, {@code ||}, {@code and} and {@code or}.
 */
@Component
public class PhpLanguageSupport extends TreeSitterSupport {

    static final String FILE_PREFIX = "file:";

    private static final Set<String> FUNCTIONS = Set.of(
            "function_definition", "method_declaration", "anonymous_function", "arrow_function",
            "anonymous_function_creation_expression");
    private static final Set<String> DECISIONS = Set.of(
            "if_statement", "else_if_clause", "for_statement", "foreach_statement", "while_statement",
            "do_statement", "case_statement", "catch_clause", "conditional_expression",
            "match_conditional_expression", "&&", "||", "and", "or");
    private static final Set<String> INCLUDES = Set.of(
            "require_expression", "require_once_expression", "include_expression", "include_once_expression");

    @Override
    public String language() { return "php"; }

    @Override
    protected TSLanguage grammar() { return new TreeSitterPhp(); }

    @Override
    protected Set<String> functionTypes() { return FUNCTIONS; }

    @Override
    protected Set<String> decisionTypes() { return DECISIONS; }

    @Override
    protected void visit(TSNode node, String type, Source src, Collected out) {
        if (INCLUDES.contains(type)) { addInclude(node, src, out); return; }
        switch (type) {
            case "name" -> {
                String t = src.text(node);
                if (!t.isEmpty() && Character.isUpperCase(t.charAt(0))) out.addTypeRef(t);
            }
            case "namespace_definition" -> {
                if (!out.packageName.isEmpty()) return;
                TSNode ns = child(node, "namespace_name");
                if (ns != null) out.packageName = dotted(src.text(ns));
            }
            case "namespace_use_declaration" -> addUse(node, src, out);
            case "class_declaration" -> addType(node, "CLASS", src, out);
            case "interface_declaration", "trait_declaration" -> addType(node, "INTERFACE", src, out);
            case "enum_declaration" -> addType(node, "ENUM", src, out);
            case "function_definition", "method_declaration" -> addType(node, "METHOD", src, out);
            default -> { }
        }
    }

    private static void addType(TSNode node, String kind, Source src, Collected out) {
        TSNode name = child(node, "name");
        if (name != null) out.symbols.add(new Symbol(src.text(name), kind, src.line(name)));
    }

    private static void addUse(TSNode node, Source src, Collected out) {
        String prefix = "";
        for (int i = 0; i < node.getChildCount(); i++) {
            String t = node.getChild(i).getType();
            if (t.equals("function") || t.equals("const")) return; // not a class
            if (t.equals("namespace_name")) prefix = src.text(node.getChild(i)) + "\\";
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            TSNode c = node.getChild(i);
            if (c.getType().equals("namespace_use_clause")) addClause(c, prefix, src, out);
            else if (c.getType().equals("namespace_use_group")) {
                for (int j = 0; j < c.getChildCount(); j++) {
                    if (c.getChild(j).getType().equals("namespace_use_clause")) addClause(c.getChild(j), prefix, src, out);
                }
            }
        }
    }

    /** The first child is the name; a second child, if any, is the alias. */
    private static void addClause(TSNode clause, String prefix, Source src, Collected out) {
        if (clause.getChildCount() == 0) return;
        String name = dotted(prefix + src.text(clause.getChild(0)));
        if (!name.isEmpty()) out.imports.add(new RawImport(name, false, false));
    }

    private static void addInclude(TSNode node, Source src, Collected out) {
        TSNode str = null;
        for (int i = 0; i < node.getChildCount() && str == null; i++) {
            TSNode c = node.getChild(i);
            if (c.getType().equals("string")) str = c;
            else if (c.getType().equals("binary_expression")) str = child(c, "string"); // __DIR__ . '/x.php'
        }
        if (str == null) return;
        TSNode content = child(str, "string_content");
        if (content == null) return;
        String path = src.text(content).trim();
        while (path.startsWith("/") || path.startsWith("./")) path = path.substring(path.startsWith("/") ? 1 : 2);
        if (!path.isEmpty()) out.imports.add(new RawImport(FILE_PREFIX + "./" + path, false, false));
    }

    private static String dotted(String name) {
        String s = name.trim().replace('\\', '.');
        while (s.startsWith(".")) s = s.substring(1);
        return s;
    }
}
