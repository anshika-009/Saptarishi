package com.saptarishi.backend.parse;

import com.saptarishi.backend.parse.ParsedFile.RawImport;
import com.saptarishi.backend.parse.ParsedFile.Symbol;
import java.util.ArrayList;
import java.util.List;
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
 */
@Component
public class RustLanguageSupport extends TreeSitterSupport {

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
}
