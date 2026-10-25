package com.saptarishi.backend.parse;

import com.saptarishi.backend.parse.ParsedFile.RawImport;
import com.saptarishi.backend.parse.ParsedFile.Symbol;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.List;
import java.util.Set;
import org.treesitter.TSLanguage;
import org.treesitter.TSNode;
import org.treesitter.TSParser;
import org.treesitter.TSTree;

/**
 * Shared machinery for languages read with a tree-sitter grammar. A language supplies its grammar, which
 * node types count as functions and as decision points, and a hook that is called for every node to pick
 * out imports and symbols. Complexity follows the Java definition: one point per function plus one per
 * decision point, so scores are comparable across languages.
 *
 * <p>tree-sitter recovers from syntax errors, so a file with a mistake still parses; only an exception
 * or an oversized file gives an unparsed node.
 */
abstract class TreeSitterSupport implements LanguageSupport {

    static final int MAX_CHARS = 1_000_000;
    private static final String COMMENT = "comment";

    /** A new grammar object (cheap: the native library is loaded once). */
    protected abstract TSLanguage grammar();

    /** Node types that are a function, method or function literal. */
    protected abstract Set<String> functionTypes();

    /** Node types that add one to the complexity: branches, loops, case labels, "&&" and "||". */
    protected abstract Set<String> decisionTypes();

    /** Called once for every node outside comments. Add to {@code out}. */
    protected abstract void visit(TSNode node, String type, Source src, Collected out);

    protected String packageName(TSNode root, Source src) { return ""; }

    /** What the hook may fill in. */
    static final class Collected {
        final List<RawImport> imports = new ArrayList<>();
        final List<Symbol> symbols = new ArrayList<>();
    }

    /** The file's text as UTF-8 bytes, because tree-sitter reports positions in bytes. */
    static final class Source {
        private final byte[] bytes;
        private final int[] lineStarts;

        Source(String content) {
            this.bytes = content.getBytes(StandardCharsets.UTF_8);
            int[] starts = new int[16];
            int n = 1;
            for (int i = 0; i < bytes.length; i++) {
                if (bytes[i] == '\n') {
                    if (n == starts.length) starts = Arrays.copyOf(starts, n * 2);
                    starts[n++] = i + 1;
                }
            }
            this.lineStarts = Arrays.copyOf(starts, n);
        }

        String text(TSNode node) {
            int start = node.getStartByte();
            int end = node.getEndByte();
            if (start < 0 || end > bytes.length || start > end) return "";
            return new String(bytes, start, end - start, StandardCharsets.UTF_8);
        }

        /** 1-based line the node starts on. */
        int line(TSNode node) {
            int i = Arrays.binarySearch(lineStarts, node.getStartByte());
            return i >= 0 ? i + 1 : -i - 1;
        }
    }

    /** The first direct child with this type, or null. */
    static TSNode child(TSNode node, String type) {
        for (int i = 0; i < node.getChildCount(); i++) {
            TSNode c = node.getChild(i);
            if (type.equals(c.getType())) return c;
        }
        return null;
    }

    @Override
    public ParsedFile parse(String path, String content) {
        int loc = (int) content.lines().filter(l -> !l.isBlank()).count();
        if (content.length() > MAX_CHARS) return ParsedFile.unparsed(path, language(), loc);
        try {
            TSParser parser = new TSParser(); // not thread-safe, and files are parsed in parallel
            parser.setLanguage(grammar());
            TSTree tree = parser.parseString(null, content);
            TSNode root = tree.getRootNode();
            Source src = new Source(content);
            Collected out = new Collected();

            int tokens = 0;
            int functions = 0;
            int decisions = 0;
            Deque<TSNode> stack = new ArrayDeque<>();
            stack.push(root);
            while (!stack.isEmpty()) {
                TSNode node = stack.pop();
                String type = node.getType();
                if (COMMENT.equals(type)) continue;
                int children = node.getChildCount();
                if (children == 0) tokens++;
                if (functionTypes().contains(type)) functions++;
                if (decisionTypes().contains(type)) decisions++;
                visit(node, type, src, out);
                for (int i = children - 1; i >= 0; i--) stack.push(node.getChild(i));
            }
            out.symbols.sort((a, b) -> Integer.compare(a.line(), b.line()));
            return new ParsedFile(path, language(), packageName(root, src), out.imports, out.symbols,
                    tokens, functions + decisions, loc, true);
        } catch (RuntimeException | StackOverflowError e) {
            return ParsedFile.unparsed(path, language(), loc);
        }
    }
}
