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
 * Python, read with patterns rather than a full parser (comments and strings are blanked first).
 *
 * <p>Imports are reported as dotted module names. A relative import keeps its leading dots
 * ({@code from ..pkg import a} gives {@code ..pkg} and {@code ..pkg.a}), and {@code from pkg import name}
 * reports both {@code pkg} and {@code pkg.name}, because {@code name} may be a submodule; the graph
 * builder keeps whichever of them is a file in the repository.
 *
 * <p>Complexity follows the Java definition: one point per {@code def}, plus one per decision point
 * (if, elif, for, while, except, case, and, or). Comprehension and conditional-expression clauses use
 * the same keywords, so they count too.
 */
@Component
public class PythonLanguageSupport implements LanguageSupport {

    static final int MAX_CHARS = 1_000_000;

    private static final Pattern IMPORT = Pattern.compile(
            "^[ \\t]*import[ \\t]+([\\w., \\t]+)$", Pattern.MULTILINE);
    private static final Pattern FROM = Pattern.compile(
            "^[ \\t]*from[ \\t]+(\\.*[\\w.]*)[ \\t]+import[ \\t]+(\\*|\\([^)]*\\)|[^\\n]*)", Pattern.MULTILINE);
    private static final Pattern DEF = Pattern.compile("^[ \\t]*(?:async[ \\t]+)?def[ \\t]+(\\w+)", Pattern.MULTILINE);
    private static final Pattern CLASS = Pattern.compile("^[ \\t]*class[ \\t]+(\\w+)", Pattern.MULTILINE);
    private static final Pattern DECISION = Pattern.compile(
            "(?<![.\\w])(?:if|elif|for|while|except|and|or)(?!\\w)|^[ \\t]*case[ \\t]+[^\\n]*:", Pattern.MULTILINE);
    private static final Pattern TOKEN = Pattern.compile("\\w+|\\S");

    @Override
    public String language() { return "python"; }

    @Override
    public ParsedFile parse(String path, String content) {
        int loc = (int) content.lines().filter(l -> !l.isBlank()).count();
        if (content.length() > MAX_CHARS) return ParsedFile.unparsed(path, language(), loc);
        try {
            String code = CodeScrubber.python(content);

            List<Symbol> symbols = new ArrayList<>();
            Matcher m = CLASS.matcher(code);
            while (m.find()) symbols.add(new Symbol(m.group(1), "CLASS", EcmaScriptSupport.lineAt(code, m.start(1))));
            int functions = 0;
            m = DEF.matcher(code);
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

            return new ParsedFile(path, language(), "", imports(code), symbols, tokens,
                    functions + decisions, loc, true);
        } catch (RuntimeException | StackOverflowError e) {
            return ParsedFile.unparsed(path, language(), loc);
        }
    }

    private static List<RawImport> imports(String code) {
        String joined = code.replace("\\\n", " "); // backslash line continuations
        Set<String> plain = new LinkedHashSet<>();
        List<RawImport> out = new ArrayList<>();

        Matcher m = IMPORT.matcher(joined);
        while (m.find()) {
            for (String part : m.group(1).split(",")) {
                String name = part.trim().split("\\s+")[0]; // drop "as alias"
                if (!name.isEmpty() && plain.add(name)) out.add(new RawImport(name, false, false));
            }
        }
        m = FROM.matcher(joined);
        while (m.find()) {
            String module = m.group(1);
            String names = m.group(2).trim();
            if (module.isEmpty() || module.chars().allMatch(ch -> ch == '.') && names.isEmpty()) continue;
            if (names.equals("*")) {
                if (plain.add(module + "*")) out.add(new RawImport(module, false, true));
                continue;
            }
            if (plain.add(module)) out.add(new RawImport(module, false, false));
            if (names.startsWith("(")) names = names.substring(1, names.endsWith(")") ? names.length() - 1 : names.length());
            for (String part : names.split(",")) {
                String name = part.trim().split("\\s+")[0];
                if (name.isEmpty() || !name.matches("\\w+")) continue;
                String full = module.endsWith(".") ? module + name : module + "." + name;
                if (plain.add(full)) out.add(new RawImport(full, false, false));
            }
        }
        return out;
    }
}
