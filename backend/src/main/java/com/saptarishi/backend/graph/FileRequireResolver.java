package com.saptarishi.backend.graph;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Turns the file named by a Ruby {@code require} or a PHP {@code require}/{@code include} into a file in the
 * repository. Only files with the given extension ({@code rb} or {@code php}) are targets, and the extension
 * may be left off the name.
 *
 * <p>A name that starts with {@code ./} or {@code ../} is relative to the including file
 * ({@code require_relative 'x'} and PHP's {@code __DIR__ . '/x.php'} arrive in that form). Any other name
 * ({@code require 'my_app/user'}) is searched like a load path: walking up from the including file's folder
 * to the root, at each level its {@code lib/} folder and then the level itself. If nothing is found, a file
 * whose path ends with the name is used when it is the only one, or the one sharing the most folders with
 * the including file if that is unambiguous; a bare name must share at least one folder, so gems and
 * standard libraries ({@code require 'json'}) are not linked to an unrelated file of the same name.
 */
public final class FileRequireResolver {

    private final String ext;
    private final Set<String> paths = new HashSet<>();
    private final Map<String, List<String>> byTail = new HashMap<>();

    public FileRequireResolver(Collection<String> allPaths, String extension) {
        this.ext = extension.toLowerCase(Locale.ROOT);
        for (String p : allPaths) {
            if (!p.toLowerCase(Locale.ROOT).endsWith("." + ext) || !paths.add(p)) continue;
            int start = 0;
            while (true) {
                byTail.computeIfAbsent(p.substring(start), k -> new ArrayList<>(2)).add(p);
                int slash = p.indexOf('/', start);
                if (slash < 0) break;
                start = slash + 1;
            }
        }
    }

    public Optional<String> resolve(String fromPath, String name) {
        if (name == null) return Optional.empty();
        String n = name.trim().replace('\\', '/');
        if (n.isEmpty() || n.startsWith("/")) return Optional.empty();
        String own = dirOf(fromPath);
        String hit = null;
        if (n.startsWith("./") || n.startsWith("../")) {
            hit = find(join(own, n), fromPath);
        } else {
            for (String level = own; hit == null; level = dirOf(level)) {
                hit = find(join(join(level, "lib"), n), fromPath);
                if (hit == null) hit = find(join(level, n), fromPath);
                if (level.isEmpty()) break;
            }
        }
        if (hit == null) hit = byTail(fromPath, n);
        return Optional.ofNullable(hit);
    }

    private String find(String candidate, String fromPath) {
        if (candidate == null || candidate.isEmpty()) return null;
        String file = withExt(candidate);
        return paths.contains(file) && !file.equals(fromPath) ? file : null;
    }

    private String byTail(String fromPath, String name) {
        String tail = name;
        while (tail.startsWith("./") || tail.startsWith("../")) tail = tail.substring(tail.startsWith("./") ? 2 : 3);
        tail = normalize(tail);
        if (tail == null || tail.isEmpty()) return null;
        List<String> candidates = byTail.get(withExt(tail));
        if (candidates == null) return null;
        boolean bare = tail.indexOf('/') < 0;
        String own = dirOf(fromPath);
        String best = null;
        int bestShared = -1;
        boolean tie = false;
        for (String c : candidates) {
            if (c.equals(fromPath)) continue;
            int shared = sharedFolders(own, dirOf(c));
            if (shared > bestShared) { best = c; bestShared = shared; tie = false; }
            else if (shared == bestShared) tie = true;
        }
        if (best == null || tie || (bare && bestShared < 1)) return null;
        return best;
    }

    private String withExt(String p) {
        return p.toLowerCase(Locale.ROOT).endsWith("." + ext) ? p : p + "." + ext;
    }

    private static String join(String dir, String rel) {
        return normalize(dir.isEmpty() ? rel : dir + "/" + rel);
    }

    private static String normalize(String path) {
        List<String> out = new ArrayList<>();
        for (String part : path.split("/")) {
            if (part.isEmpty() || part.equals(".")) continue;
            if (part.equals("..")) {
                if (out.isEmpty()) return null;
                out.remove(out.size() - 1);
            } else {
                out.add(part);
            }
        }
        return String.join("/", out);
    }

    private static int sharedFolders(String a, String b) {
        if (a.isEmpty() || b.isEmpty()) return 0;
        String[] x = a.split("/");
        String[] y = b.split("/");
        int n = 0;
        while (n < x.length && n < y.length && x[n].equals(y[n])) n++;
        return n;
    }

    private static String dirOf(String path) {
        int slash = path.lastIndexOf('/');
        return slash < 0 ? "" : path.substring(0, slash);
    }
}
