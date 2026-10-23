package com.saptarishi.backend.pathfinder;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Guesses which files an issue is about, from its text alone. HIGH: a path or a stack frame.
 * MEDIUM: a class or type name that exists once in the repo. LOW: a method name that exists once.
 * Anything ambiguous (the same name in several files) is ignored rather than guessed.
 */
public final class IssueMapper {

    public enum Confidence { HIGH, MEDIUM, LOW }

    public record Match(String path, Confidence confidence, String reason) {}

    public record Symbol(String name, String kind, String path) {}

    private static final Pattern FILE = Pattern.compile("([A-Za-z0-9_./\\\\-]*[A-Za-z0-9_-]\\.[A-Za-z0-9]{1,5})(?::(\\d+))?");
    private static final Pattern TYPE = Pattern.compile("\\b[A-Z][A-Za-z0-9]{2,}\\b");
    private static final Pattern METHOD = Pattern.compile("\\b[a-z][a-z0-9]*[A-Z][A-Za-z0-9]*\\b");
    private static final Set<String> TYPE_KINDS = Set.of("CLASS", "INTERFACE", "ENUM", "RECORD");
    private static final Set<String> STOP = Set.of(
            "toString", "hashCode", "equals", "getMessage", "isEmpty", "getName", "setName", "getId", "setId",
            "valueOf", "getClass", "getValue", "setValue", "iterator");
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
            } else if ("METHOD".equals(s.kind())) {
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
                for (String p : candidates) {
                    if (p.equals(token) || p.endsWith("/" + token) || token.endsWith("/" + p)) {
                        add(best, p, Confidence.HIGH, "mentions " + p);
                    }
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
            if (declaring != null && declaring.size() == 1) {
                add(best, declaring.iterator().next(), Confidence.MEDIUM, "mentions type " + word);
            }
        }

        Matcher m = METHOD.matcher(text);
        while (m.find()) {
            String word = m.group();
            if (word.length() < 6 || STOP.contains(word)) continue;
            Set<String> declaring = methodToPaths.get(word);
            if (declaring != null && declaring.size() == 1) {
                add(best, declaring.iterator().next(), Confidence.LOW, "mentions method " + word + "()");
            }
        }

        return best.values().stream()
                .sorted(Comparator.comparing(Match::confidence))
                .limit(MAX_FILES)
                .toList();
    }

    private static void add(Map<String, Match> best, String path, Confidence c, String reason) {
        best.merge(path, new Match(path, c, reason),
                (a, b) -> b.confidence().compareTo(a.confidence()) < 0 ? b : a);
    }
}