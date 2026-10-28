package com.saptarishi.backend.parse;

import com.saptarishi.backend.parse.ParsedFile.CallSite;
import com.saptarishi.backend.parse.ParsedFile.MethodDecl;
import com.saptarishi.backend.parse.ParsedFile.RawImport;
import com.saptarishi.backend.parse.ParsedFile.Symbol;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.LinkedHashSet;
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
    private static final String ERROR = "ERROR";

    /** A new grammar object (cheap: the native library is loaded once). */
    protected abstract TSLanguage grammar();

    /** Grammar for one file. Override when a language needs several (TypeScript vs TSX). */
    protected TSLanguage grammar(String path) {
        return grammar();
    }

    /** Node types that are a function, method or function literal. */
    protected abstract Set<String> functionTypes();

    /** Node types that add one to the complexity: branches, loops, case labels, "&&" and "||". */
    protected abstract Set<String> decisionTypes();

    /** Called once for every node outside comments. Add to {@code out}. */
    protected abstract void visit(TSNode node, String type, Source src, Collected out);

    /** Whether this node adds one to the complexity. Override when the node type alone is not enough. */
    protected boolean isDecision(TSNode node, String type, Source src) {
        return decisionTypes().contains(type);
    }

    /**
     * Whether this node is a function and adds one to the complexity. Override when the node type alone is
     * not enough, e.g. a callback arrow is not counted but an arrow assigned to a name is.
     */
    protected boolean isFunction(TSNode node, String type, Source src) {
        return functionTypes().contains(type);
    }

    /** Whether this node type is a comment (not counted as a token, not visited). Grammars differ in the name. */
    protected boolean isComment(String type) {
        return COMMENT.equals(type);
    }

    /**
     * Whether this node type counts as a single token and has its children skipped (a string literal is one
     * token to a lexer, but a tree of quote, fragment, escape and quote to tree-sitter).
     */
    protected boolean isAtomic(String type) {
        return false;
    }

    /**
     * Whether a file with a syntax error is reported as unparsed. tree-sitter recovers and carries on, which
     * most languages want; a language whose earlier parser rejected such files can keep that behaviour.
     */
    protected boolean rejectsSyntaxErrors() {
        return false;
    }

    /**
     * Called once after the walk, for facts that need the shape around a node (the enclosing method, the
     * type a call is made on) and so do not fit a per-node hook. Fill in {@code out}.
     */
    protected void finish(TSNode root, Source src, Collected out) { }

    /** What the hook may fill in. */
    static final class Collected {
        static final int MAX_TYPE_REFS = 5000;
        final List<RawImport> imports = new ArrayList<>();
        final List<Symbol> symbols = new ArrayList<>();
        /** Methods declared and calls made from them, for the call graph. Empty for most languages. */
        final List<MethodDecl> methods = new ArrayList<>();
        final List<CallSite> calls = new ArrayList<>();
        /** Replaces the default (functions + decision points) when a language counts differently; null = default. */
        Integer complexity;
        /** Names this file mentions, for languages that link files by the types they use. */
        final Set<String> typeRefs = new LinkedHashSet<>();
        /** Namespace or package, "" if none. */
        String packageName = "";

        void addTypeRef(String name) {
            if (typeRefs.size() < MAX_TYPE_REFS) typeRefs.add(name);
        }
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

    /**
     * tree-sitter reports a syntax error in one of two ways: an ERROR node around text it could not read, or a
     * MISSING node, a token it inserted itself (a forgotten ";", "}" or ")"). A MISSING node is a leaf with
     * no width, which valid code never has, so it is told apart by that. The root is exempt (an empty file).
     */
    private static boolean isSyntaxError(TSNode node, String type, int children, TSNode root) {
        if (ERROR.equals(type)) return true;
        return node != root && children == 0 && node.getStartByte() == node.getEndByte();
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
            parser.setLanguage(grammar(path));
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
                if (isComment(type)) continue;
                int children = node.getChildCount();
                if (rejectsSyntaxErrors() && isSyntaxError(node, type, children, root)) {
                    return ParsedFile.unparsed(path, language(), loc);
                }
                if (children == 0 || isAtomic(type)) tokens++;
                if (isFunction(node, type, src)) functions++;
                if (isDecision(node, type, src)) decisions++;
                visit(node, type, src, out);
                if (isAtomic(type)) continue;
                for (int i = children - 1; i >= 0; i--) stack.push(node.getChild(i));
            }
            finish(root, src, out);
            out.symbols.sort((a, b) -> Integer.compare(a.line(), b.line()));
            int complexity = out.complexity != null ? out.complexity : functions + decisions;
            return new ParsedFile(path, language(), out.packageName, out.imports, out.symbols,
                    tokens, complexity, loc, true, new ArrayList<>(out.typeRefs), out.methods, out.calls);
        } catch (RuntimeException | StackOverflowError e) {
            return ParsedFile.unparsed(path, language(), loc);
        }
    }
}