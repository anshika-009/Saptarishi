package com.saptarishi.backend.pathfinder;

import com.saptarishi.backend.parse.SymbolKinds;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Guesses which files an issue is about, from its text alone. HIGH: a path or a stack frame.
 * MEDIUM: a class or type name that exists once in the repo. LOW: a method name that exists once.
 * Anything ambiguous (the same name in several files) is ignored rather than guessed.
 *
 * <p>Nothing here is specific to a language. Symbols arrive already reduced to the kinds in
 * {@link SymbolKinds}. A method is recognised in whichever style a language writes it: {@code camelCase}
 * (Java, JS, Kotlin, C#), {@code snake_case} (Python, Ruby, Rust, C), {@code PascalCase} (Go, C#), or any
 * name written as a call, {@code name()}. A stack frame is recognised as {@code File.java:42},
 * {@code file.py:42:7}, {@code File "app/x.py", line 42} and {@code Foo.cs:line 42}.
 */
public final class IssueMapper {

    public enum Confidence { HIGH, MEDIUM, LOW }

    public record Match(String path, Confidence confidence, String reason) {}

    public record Symbol(String name, String kind, String path) {}

    // a path, then optionally where in it: ":42", ":42:7", '", line 42' or ":line 42"
    private static final Pattern FILE = Pattern.compile(
            "([A-Za-z0-9_./\\\\-]*[A-Za-z0-9_-]\\.[A-Za-z0-9]{1,5})[\"']?(?:(?::(?:line\\s+)?|,\\s*line\\s+)(\\d+))?");
    private static final Pattern TYPE = Pattern.compile("\\b[A-Z][A-Za-z0-9]{2,}\\b");
    private static final Pattern CAMEL = Pattern.compile("\\b[a-z][a-z0-9]*[A-Z][A-Za-z0-9]*\\b");
    private static final Pattern SNAKE = Pattern.compile("\\b[a-z][a-z0-9]*(?:_[a-z0-9]+)+\\b");
    private static final Pattern CALL = Pattern.compile("\\b([A-Za-z_][A-Za-z0-9_]{3,})\\(");
    private static final Set<String> TYPE_KINDS = SymbolKinds.TYPES;
    /** Names that mean the same in every codebase: they say nothing about which file an issue is in. */
    private static final Set<String> STOP = Set.of(
            "toString", "hashCode", "equals", "getMessage", "isEmpty", "getName", "setName", "getId", "setId",
            "valueOf", "getClass", "getValue", "setValue", "iterator", "toJSON", "to_string", "to_s", "to_json",
            "from_json", "initialize", "constructor", "print", "println", "printf", "range", "main", "init",
            "super", "self", "push", "pop", "map", "filter", "reduce", "format", "assert", "require", "include",
            "import", "console", "len", "list", "dict");
    private static final int MIN_BARE_METHOD = 6; // a long name in prose is likely code; "save" is not
    private static final int MIN_CALLED_METHOD = 4; // "save()" is code however short
    private static final int MAX_FILES = 5;

    private final Map<String, List<String>> byFileName = new HashMap<>();
    private final Map<String, List<String>> byBase = new HashMap<>();
    private final Map<String, Set<String>> typeToPaths = new HashMap<>();
    private final Map<String, Set<String>> methodToPaths = new HashMap<>();
    private final Set<String> extensions = new HashSet<>();

    public IssueMapper(List<String> paths, List<Symbol> symbols) {
        for (String p : paths) {
            String name = p.substring(p.lastIndexOf('/') + 1);
            int dot = name.lastIndexOf('.');
            byFileName.computeIfAbsent(name, k -> new ArrayList<>()).add(p);
            if (dot > 0) {
                extensions.add(name.substring(dot + 1));
                byBase.computeIfAbsent(name.substring(0, dot), k -> new ArrayList<>()).add(p);
            }
        }
        for (Symbol s : symbols) {
            if (TYPE_KINDS.contains(s.kind())) {
                typeToPaths.computeIfAbsent(s.name(), k -> new HashSet<>()).add(s.path());
            } else if (SymbolKinds.METHOD.equals(s.kind())) {
                methodToPaths.computeIfAbsent(s.name(), k -> new HashSet<>()).add(s.path());
            }
        }
    }

    public List<Match> map(String text) {
        Map<String, Match> best = new LinkedHashMap<>();

        Matcher f = FILE.matcher(text);
        while (f.find()) {
            String token = f.group(1).replace('\\', '/');
            while (token.startsWith("./")) token = token.substring(2);
            String name = token.substring(token.lastIndexOf('/') + 1);
            int dot = name.lastIndexOf('.');
            if (dot < 0 || !extensions.contains(name.substring(dot + 1))) continue;

            List<String> candidates = byFileName.getOrDefault(name, List.of());
            if (token.contains("/")) {
                boolean found = false;
                for (String p : candidates) {
                    if (p.equals(token) || p.endsWith("/" + token) || token.endsWith("/" + p)) {
                        add(best, p, Confidence.HIGH,
                                f.group(2) != null ? "stack frame " + p + ":" + f.group(2) : "mentions " + p);
                        found = true;
                    }
                }
                // a trace carries the build machine's folders (C:\src\X.cs); a line number on a file name
                // that exists once in the repo is still a frame
                if (!found && f.group(2) != null && candidates.size() == 1) {
                    add(best, candidates.get(0), Confidence.HIGH,
                            "stack frame " + name + ":" + f.group(2));
                }
            } else if (candidates.size() == 1) {
                boolean frame = f.group(2) != null;
                add(best, candidates.get(0), frame ? Confidence.HIGH : Confidence.MEDIUM,
                        frame ? "stack frame " + token + ":" + f.group(2) : "mentions " + token);
            }
        }

        Matcher t = TYPE.matcher(text);
        while (t.find()) {
            String word = t.group();
            List<String> files = byBase.get(word);
            if (files != null) {
                if (files.size() == 1) add(best, files.get(0), Confidence.MEDIUM, "mentions class " + word);
                continue;
            }
            Set<String> declaring = typeToPaths.get(word);
            if (declaring != null) {
                if (declaring.size() == 1) add(best, declaring.iterator().next(), Confidence.MEDIUM, "mentions type " + word);
                continue;
            }
            addMethod(best, word, MIN_BARE_METHOD); // PascalCase functions: Go, C#
        }

        Set<String> bare = new LinkedHashSet<>();
        for (Pattern p : List.of(CAMEL, SNAKE)) {
            Matcher m = p.matcher(text);
            while (m.find()) bare.add(m.group());
        }
        for (String word : bare) addMethod(best, word, MIN_BARE_METHOD);
        Matcher call = CALL.matcher(text);
        while (call.find()) addMethod(best, call.group(1), MIN_CALLED_METHOD);

        return best.values().stream()
                .sorted(Comparator.comparing(Match::confidence))
                .limit(MAX_FILES)
                .toList();
    }

    /** LOW: a method of this name declared in exactly one file. */
    private void addMethod(Map<String, Match> best, String word, int minLength) {
        if (word.length() < minLength || STOP.contains(word)) return;
        Set<String> declaring = methodToPaths.get(word);
        if (declaring != null && declaring.size() == 1) {
            add(best, declaring.iterator().next(), Confidence.LOW, "mentions method " + word + "()");
        }
    }

    private static void add(Map<String, Match> best, String path, Confidence c, String reason) {
        best.merge(path, new Match(path, c, reason),
                (a, b) -> b.confidence().compareTo(a.confidence()) < 0 ? b : a);
    }
}