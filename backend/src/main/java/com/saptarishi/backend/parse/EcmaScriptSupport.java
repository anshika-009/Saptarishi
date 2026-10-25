package com.saptarishi.backend.parse;

import com.saptarishi.backend.parse.ParsedFile.RawImport;
import com.saptarishi.backend.parse.ParsedFile.Symbol;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * JavaScript and TypeScript, read with patterns rather than a full parser. Comments, strings and regular
 * expressions are blanked first ({@link CodeScrubber}), so the patterns only see code.
 *
 * <p>What it finds: imports ({@code import}, {@code export ... from}, {@code require()}, dynamic
 * {@code import()}), symbols (classes, interfaces, enums, type aliases, functions, arrow functions
 * assigned to a name, class methods), lines of code, and complexity.
 *
 * <p>Complexity follows the Java definition so the numbers sit on the same scale: one point for every
 * function and method, plus one for every decision point (if, for, while, case, catch, ?:, &amp;&amp;,
 * ||, ??). Callback arrows such as {@code items.map(x => ...)} are not counted as functions. This is an
 * approximation: it can be fooled by unusual formatting, and it does not see inside template strings.
 */
abstract class EcmaScriptSupport implements LanguageSupport {

    /** Larger files are almost always bundles or generated code; they are kept as plain nodes. */
    static final int MAX_CHARS = 1_000_000;

    private static final String NAME = "[A-Za-z_$][\\w$]*";

    private static final Pattern IMPORT_FROM = Pattern.compile(
            "\\bimport\\s+(?:[\\w$*\\s{},]+?\\s+from\\s+)?['\"]([^'\"\\n]+)['\"]");
    private static final Pattern EXPORT_FROM = Pattern.compile(
            "\\bexport\\s+(?:type\\s+)?(?:\\*(?:\\s+as\\s+" + NAME + ")?|\\{[^}]*\\})\\s*from\\s*['\"]([^'\"\\n]+)['\"]");
    private static final Pattern REQUIRE = Pattern.compile(
            "(?<![.\\w$])require\\s*\\(\\s*['\"]([^'\"\\n]+)['\"]\\s*\\)");
    private static final Pattern DYNAMIC_IMPORT = Pattern.compile(
            "\\bimport\\s*\\(\\s*['\"]([^'\"\\n]+)['\"]\\s*\\)");

    private static final Pattern CLASS = Pattern.compile("(?<![.\\w$])class\\s+(" + NAME + ")");
    private static final Pattern INTERFACE = Pattern.compile("(?<![.\\w$])interface\\s+(" + NAME + ")");
    private static final Pattern ENUM = Pattern.compile("(?<![.\\w$])enum\\s+(" + NAME + ")");
    private static final Pattern TYPE_ALIAS = Pattern.compile(
            "(?<![.\\w$])type\\s+(" + NAME + ")\\s*(?:<[^=\\n]*>)?\\s*=");
    private static final Pattern FUNCTION_KEYWORD = Pattern.compile("(?<![.\\w$])function\\b\\s*\\*?\\s*(" + NAME + ")?");
    private static final Pattern ARROW_ASSIGNED = Pattern.compile(
            "(?<![.\\w$])(?:const|let|var)\\s+(" + NAME + ")\\s*(?::[^=;\\n]+)?=\\s*(?:async\\s*)?"
                    + "(?:<[^>\\n]*>\\s*)?(?:\\((?:[^()]|\\([^()]*\\))*\\)|" + NAME + ")\\s*(?::\\s*[^=\\n]+?)?=>");
    private static final Pattern PROPERTY_ARROW = Pattern.compile(
            "(?<![.\\w$])(" + NAME + ")\\s*[:=]\\s*(?:async\\s*)?(?:\\((?:[^()]|\\([^()]*\\))*\\)|" + NAME + ")\\s*=>");
    private static final Pattern CLASS_METHOD = Pattern.compile(
            "^[ \\t]*(?:(?:public|private|protected|static|async|override|readonly|abstract|get|set)\\s+)*\\*?\\s*"
                    + "(" + NAME + "|#" + NAME + ")\\s*(?:<[^>\\n]*>)?\\s*\\((?![^;{}]*(?:\\bfunction\\b|=>))[^;{}]*\\)"
                    + "\\s*(?::\\s*[^{;=]+)?\\{", Pattern.MULTILINE);
    private static final Set<String> NOT_METHODS = Set.of(
            "if", "for", "while", "switch", "catch", "function", "return", "with", "else", "do", "typeof",
            "await", "new", "throw", "super", "import", "require", "delete", "void", "yield");

    private static final Pattern DECISION = Pattern.compile(
            "(?<![.\\w$])(?:if|for|while|catch|case)\\b|&&|\\|\\||\\?\\?|(?<=[\\s)\\]])\\?(?![.?:])");
    private static final Pattern TOKEN = Pattern.compile("[A-Za-z_$][\\w$]*|\\d[\\w.]*|\\S");

    @Override
    public ParsedFile parse(String path, String content) {
        int loc = (int) content.lines().filter(l -> !l.isBlank()).count();
        if (content.length() > MAX_CHARS) return ParsedFile.unparsed(path, language(), loc);
        try {
            String code = CodeScrubber.javaScript(content, false);
            String withStrings = CodeScrubber.javaScript(content, true);

            List<Symbol> symbols = new ArrayList<>();
            Set<Integer> named = new LinkedHashSet<>(); // positions already reported, so one function is one symbol
            collect(code, CLASS, "CLASS", symbols, named);
            collect(code, INTERFACE, "INTERFACE", symbols, named);
            collect(code, ENUM, "ENUM", symbols, named);
            collect(code, TYPE_ALIAS, "INTERFACE", symbols, named);

            int functions = 0;
            Matcher m = FUNCTION_KEYWORD.matcher(code);
            while (m.find()) {
                functions++;
                if (m.group(1) != null) symbols.add(new Symbol(m.group(1), "METHOD", lineAt(code, m.start())));
            }
            for (Pattern p : List.of(ARROW_ASSIGNED, PROPERTY_ARROW)) {
                m = p.matcher(code);
                while (m.find()) {
                    if (!named.add(m.start(1))) continue;
                    functions++;
                    symbols.add(new Symbol(m.group(1), "METHOD", lineAt(code, m.start(1))));
                }
            }
            m = CLASS_METHOD.matcher(code);
            while (m.find()) {
                String name = m.group(1);
                if (NOT_METHODS.contains(name) || !named.add(m.start(1))) continue;
                functions++;
                symbols.add(new Symbol(name, "constructor".equals(name) ? "CONSTRUCTOR" : "METHOD",
                        lineAt(code, m.start(1))));
            }
            symbols.sort((a, b) -> Integer.compare(a.line(), b.line()));

            int decisions = 0;
            m = DECISION.matcher(code);
            while (m.find()) decisions++;

            int tokens = 0;
            m = TOKEN.matcher(code);
            while (m.find()) tokens++;

            return new ParsedFile(path, language(), "", imports(withStrings, code), symbols, tokens,
                    functions + decisions, loc, true);
        } catch (RuntimeException | StackOverflowError e) {
            return ParsedFile.unparsed(path, language(), loc);
        }
    }

    private static void collect(String code, Pattern p, String kind, List<Symbol> out, Set<Integer> named) {
        Matcher m = p.matcher(code);
        while (m.find()) {
            named.add(m.start(1));
            out.add(new Symbol(m.group(1), kind, lineAt(code, m.start(1))));
        }
    }

    /**
     * withStrings still has string contents (the specifiers); code does not. A match is real only if
     * its first characters survive in code, which rules out "import x from 'y'" written inside a string.
     */
    private static List<RawImport> imports(String withStrings, String code) {
        Set<String> specs = new LinkedHashSet<>();
        for (Pattern p : List.of(IMPORT_FROM, EXPORT_FROM, REQUIRE, DYNAMIC_IMPORT)) {
            Matcher m = p.matcher(withStrings);
            while (m.find()) {
                String head = withStrings.substring(m.start(), Math.min(m.start() + 3, withStrings.length()));
                if (code.startsWith(head, m.start())) specs.add(m.group(1));
            }
        }
        return specs.stream().map(s -> new RawImport(s, false, false)).toList();
    }

    static int lineAt(String text, int index) {
        int line = 1;
        for (int i = 0; i < index; i++) if (text.charAt(i) == '\n') line++;
        return line;
    }
}
