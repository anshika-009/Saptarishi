package com.saptarishi.backend.parse;

import com.saptarishi.backend.parse.ParsedFile.RawImport;
import com.saptarishi.backend.parse.ParsedFile.Symbol;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * Kotlin, read with patterns because no Kotlin grammar is bundled (comments and strings are blanked first).
 *
 * <p>The {@code package} is the file's package name and {@code import a.b.C} / {@code import a.b.*} are its
 * imports, reported as {@code a.b.C} and {@code a.b}; as with C#, a package is not a file, so
 * {@code CSharpImportResolver} links a file to the files of the package that declare a type it mentions.
 * Capitalised identifiers are kept as the types the file mentions.
 *
 * <p>Symbols: {@code class}, {@code object} (CLASS), {@code interface} (INTERFACE), {@code enum class}
 * (ENUM), and every {@code fun} (METHOD). Complexity follows the Java definition: one per {@code fun}, plus
 * one per {@code if}, {@code for}, {@code while}, {@code catch}, {@code when}, {@code &&}, {@code ||} and
 * {@code ?:}.
 */
@Component
public class KotlinLanguageSupport implements LanguageSupport {

    static final int MAX_CHARS = 1_000_000;
    private static final int MAX_TYPE_REFS = 5000;

    private static final Pattern PACKAGE = Pattern.compile("^[ \\t]*package[ \\t]+([\\w.]+)", Pattern.MULTILINE);
    private static final Pattern IMPORT = Pattern.compile(
            "^[ \\t]*import[ \\t]+([\\w.]+?)(\\.\\*)?(?:[ \\t]+as[ \\t]+\\w+)?[ \\t]*;?[ \\t]*\\r?$", Pattern.MULTILINE);
    private static final Pattern TYPE = Pattern.compile(
            "^[ \\t]*((?:(?:public|private|protected|internal|abstract|open|final|sealed|data|enum|annotation|inner"
                    + "|value|inline|fun|expect|actual|companion)[ \\t]+)*)(class|interface|object)[ \\t]+(\\w+)",
            Pattern.MULTILINE);
    private static final Pattern FUN = Pattern.compile(
            "(?<![\\w.])fun[ \\t]+(?:<[^>]*>[ \\t]*)?(?:[\\w<>?,.]+\\.)?(\\w+)[ \\t]*\\(");
    private static final Pattern DECISION = Pattern.compile(
            "(?<![\\w.])(?:if|for|while|catch|when)(?!\\w)|&&|\\|\\||\\?:");
    private static final Pattern TOKEN = Pattern.compile("\\w+|\\S");
    private static final Pattern TYPE_REF = Pattern.compile("\\b[A-Z]\\w*");

    @Override
    public String language() { return "kotlin"; }

    @Override
    public ParsedFile parse(String path, String content) {
        int loc = (int) content.lines().filter(l -> !l.isBlank()).count();
        if (content.length() > MAX_CHARS) return ParsedFile.unparsed(path, language(), loc);
        try {
            String code = scrub(content);

            String pkg = "";
            Matcher m = PACKAGE.matcher(code);
            if (m.find()) pkg = m.group(1);

            List<RawImport> imports = new ArrayList<>();
            m = IMPORT.matcher(code);
            while (m.find()) imports.add(new RawImport(m.group(1), false, false)); // a wildcard is just the package name

            List<Symbol> symbols = new ArrayList<>();
            m = TYPE.matcher(code);
            while (m.find()) {
                String kind = m.group(2).equals("interface") ? "INTERFACE"
                        : m.group(1).contains("enum") ? "ENUM" : "CLASS";
                symbols.add(new Symbol(m.group(3), kind, EcmaScriptSupport.lineAt(code, m.start(3))));
            }
            int functions = 0;
            m = FUN.matcher(code);
            while (m.find()) {
                functions++;
                symbols.add(new Symbol(m.group(1), "METHOD", EcmaScriptSupport.lineAt(code, m.start(1))));
            }
            symbols.sort((a, b) -> Integer.compare(a.line(), b.line()));

            int decisions = 0;
            m = DECISION.matcher(code);
            while (m.find()) decisions++;
            int tokens = 0;
            m = TOKEN.matcher(code);
            while (m.find()) tokens++;

            Set<String> refs = new LinkedHashSet<>();
            m = TYPE_REF.matcher(code);
            while (m.find() && refs.size() < MAX_TYPE_REFS) refs.add(m.group());

            return new ParsedFile(path, language(), pkg, imports, symbols, tokens, functions + decisions, loc,
                    true, new ArrayList<>(refs));
        } catch (RuntimeException | StackOverflowError e) {
            return ParsedFile.unparsed(path, language(), loc);
        }
    }

    /** Blanks comments, strings (including raw """ strings) and character literals; keeps length and newlines. */
    static String scrub(String src) {
        char[] out = src.toCharArray();
        int n = out.length;
        int i = 0;
        while (i < n) {
            char c = out[i];
            if (c == '/' && i + 1 < n && out[i + 1] == '/') {
                while (i < n && out[i] != '\n') out[i++] = ' ';
            } else if (c == '/' && i + 1 < n && out[i + 1] == '*') {
                out[i++] = ' ';
                out[i++] = ' ';
                while (i < n && !(out[i] == '*' && i + 1 < n && out[i + 1] == '/')) {
                    if (out[i] != '\n') out[i] = ' ';
                    i++;
                }
                if (i < n) { out[i++] = ' '; if (i < n) out[i++] = ' '; }
            } else if (c == '"' && i + 2 < n && out[i + 1] == '"' && out[i + 2] == '"') {
                out[i++] = ' ';
                out[i++] = ' ';
                out[i++] = ' ';
                while (i < n && !(out[i] == '"' && i + 2 < n && out[i + 1] == '"' && out[i + 2] == '"')) {
                    if (out[i] != '\n') out[i] = ' ';
                    i++;
                }
                for (int k = 0; k < 3 && i < n; k++) out[i++] = ' ';
            } else if (c == '"' || c == '\'') {
                char quote = c;
                out[i++] = ' ';
                while (i < n && out[i] != quote && out[i] != '\n') {
                    if (out[i] == '\\' && i + 1 < n && out[i + 1] != '\n') out[i++] = ' ';
                    out[i++] = ' ';
                }
                if (i < n && out[i] == quote) out[i++] = ' ';
            } else {
                i++;
            }
        }
        return new String(out);
    }
}
