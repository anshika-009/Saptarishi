package com.saptarishi.backend.parse;

import com.saptarishi.backend.parse.ParsedFile.RawImport;
import com.saptarishi.backend.parse.ParsedFile.Symbol;
import java.util.Set;
import org.springframework.stereotype.Component;
import org.treesitter.TSLanguage;
import org.treesitter.TSNode;
import org.treesitter.TreeSitterGo;

/**
 * Go. Imports are reported as the import path as written ({@code "example.com/app/util"}); turning a
 * path into files needs the module path from go.mod, which {@link GoModSupport} reads and
 * {@code GoImportResolver} uses. Structs and interfaces are CLASS and INTERFACE symbols; functions and
 * methods are METHOD symbols.
 *
 * <p>Complexity: one per function, method and function literal, plus one per {@code if}, {@code for},
 * {@code case} (expression, type and select), {@code &&} and {@code ||}. A {@code default} is not counted,
 * as in Java.
 */
@Component
public class GoLanguageSupport extends TreeSitterSupport {

    private static final Set<String> FUNCTIONS = Set.of("function_declaration", "method_declaration", "func_literal");
    private static final Set<String> DECISIONS = Set.of(
            "if_statement", "for_statement", "expression_case", "type_case", "communication_case", "&&", "||");

    @Override
    public String language() { return "go"; }

    @Override
    protected TSLanguage grammar() { return new TreeSitterGo(); }

    @Override
    protected Set<String> functionTypes() { return FUNCTIONS; }

    @Override
    protected Set<String> decisionTypes() { return DECISIONS; }

    @Override
    protected void visit(TSNode node, String type, Source src, Collected out) {
        switch (type) {
            case "import_spec" -> {
                TSNode lit = child(node, "interpreted_string_literal");
                if (lit == null) lit = child(node, "raw_string_literal");
                if (lit == null) return;
                String text = src.text(lit);
                if (text.length() >= 2) out.imports.add(new RawImport(text.substring(1, text.length() - 1), false, false));
            }
            case "type_spec" -> {
                TSNode name = child(node, "type_identifier");
                if (name == null) return;
                String kind = child(node, "struct_type") != null ? "CLASS"
                        : child(node, "interface_type") != null ? "INTERFACE" : null;
                if (kind != null) out.symbols.add(new Symbol(src.text(name), kind, src.line(name)));
            }
            case "function_declaration" -> addFunction(node, "identifier", src, out);
            case "method_declaration" -> addFunction(node, "field_identifier", src, out);
            default -> { }
        }
    }

    private static void addFunction(TSNode node, String nameType, Source src, Collected out) {
        TSNode name = child(node, nameType);
        if (name != null) out.symbols.add(new Symbol(src.text(name), "METHOD", src.line(name)));
    }
}
