package com.saptarishi.backend.parse;

import com.saptarishi.backend.parse.ParsedFile.RawImport;
import com.saptarishi.backend.parse.ParsedFile.Symbol;
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
 */

@Component
public class CSharpLanguageSupport extends TreeSitterSupport {

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
}
