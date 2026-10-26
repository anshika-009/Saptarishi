package com.saptarishi.backend.parse;

import com.saptarishi.backend.parse.ParsedFile.RawImport;
import com.saptarishi.backend.parse.ParsedFile.Symbol;
import java.util.Set;
import org.springframework.stereotype.Component;
import org.treesitter.TSLanguage;
import org.treesitter.TSNode;
import org.treesitter.TreeSitterRuby;

/**
 * Ruby. Imports are the files named by {@code require}, {@code require_relative} and {@code load} with a plain
 * string. {@code require_relative 'x'} is reported as {@code ./x} (relative to the file); anything else is
 * reported as written. {@code FileRequireResolver} turns the name into a file.
 *
 * <p>Classes and modules are CLASS symbols, methods ({@code def} and {@code def self.x}) are METHOD.
 * Complexity: one per method, plus one per {@code if}, {@code unless}, {@code elsif}, {@code while},
 * {@code until}, {@code for}, {@code when}, {@code rescue}, their modifier forms, {@code ?:}, {@code &&},
 * {@code ||}, {@code and} and {@code or}.
 */
@Component
public class RubyLanguageSupport extends TreeSitterSupport {

    private static final Set<String> FUNCTIONS = Set.of("method", "singleton_method");
    /** Leaf tokens. */
    private static final Set<String> TOKENS = Set.of("&&", "||", "and", "or");
    /**
     * Node types whose keyword token has the same name ({@code if} the node contains an {@code if} token),
     * so only the node, which has children, is counted.
     */
    private static final Set<String> NODES = Set.of(
            "if", "unless", "elsif", "while", "until", "for", "when", "rescue",
            "if_modifier", "unless_modifier", "while_modifier", "until_modifier", "rescue_modifier", "conditional");
    private static final Set<String> LOADERS = Set.of("require", "require_relative", "load");

    @Override
    public String language() { return "ruby"; }

    @Override
    protected TSLanguage grammar() { return new TreeSitterRuby(); }

    @Override
    protected Set<String> functionTypes() { return FUNCTIONS; }

    @Override
    protected Set<String> decisionTypes() { return TOKENS; }

    @Override
    protected boolean isDecision(TSNode node, String type, Source src) {
        if (TOKENS.contains(type)) return true;
        return NODES.contains(type) && node.getChildCount() > 0;
    }

    @Override
    protected void visit(TSNode node, String type, Source src, Collected out) {
        switch (type) {
            case "call" -> addRequire(node, src, out);
            case "class", "module" -> {
                TSNode name = child(node, "constant");
                if (name != null) out.symbols.add(new Symbol(src.text(name), "CLASS", src.line(name)));
            }
            case "method" -> {
                TSNode name = child(node, "identifier");
                if (name != null) out.symbols.add(new Symbol(src.text(name), "METHOD", src.line(name)));
            }
            case "singleton_method" -> {
                TSNode name = null;
                for (int i = 0; i < node.getChildCount(); i++) {
                    if (node.getChild(i).getType().equals("identifier")) name = node.getChild(i);
                }
                if (name != null) out.symbols.add(new Symbol(src.text(name), "METHOD", src.line(name)));
            }
            default -> { }
        }
    }

    /** {@code require "x"}: no receiver (the identifier comes first) and a single plain string argument. */
    private static void addRequire(TSNode node, Source src, Collected out) {
        if (node.getChildCount() < 2) return;
        TSNode method = node.getChild(0);
        TSNode args = node.getChild(1);
        if (!method.getType().equals("identifier") || !args.getType().equals("argument_list")) return;
        String fn = src.text(method);
        if (!LOADERS.contains(fn)) return;
        TSNode str = child(args, "string");
        if (str == null || child(str, "interpolation") != null) return;
        TSNode content = child(str, "string_content");
        if (content == null) return;
        String name = src.text(content).trim();
        if (name.isEmpty()) return;
        if (fn.equals("require_relative") && !name.startsWith(".")) name = "./" + name;
        out.imports.add(new RawImport(name, false, false));
    }
}
