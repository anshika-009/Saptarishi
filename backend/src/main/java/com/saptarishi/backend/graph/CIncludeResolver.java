package com.saptarishi.backend.graph;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Turns a C or C++ {@code #include}, as written ({@code "util.h"} or {@code <net/socket.h>}), into the file
 * in the repository it refers to. System headers ({@code <stdio.h>}, {@code <vector>}) are not in the
 * repository and resolve to nothing.
 *
 * <p>A compiler searches the folders it is told about with {@code -I}, which a repository does not record.
 * This approximates the usual layouts, in order:
 * <ol>
 *   <li>Walking up from the including file's folder to the repository root, at each level the level itself,
 *       then its {@code include/}, {@code inc/} and {@code src/} folders. The including file's own folder
 *       comes first, which is where {@code "x.h"} is looked up first. For {@code <x.h>} that folder is
 *       skipped, because a compiler does not search it for angle includes.</li>
 *   <li>Otherwise, any file whose path ends with the included path ({@code "net/socket.h"} finds
 *       {@code lib/include/net/socket.h}). If several files match, the one sharing the longest folder
 *       prefix with the including file wins, and if that is a tie nothing is returned rather than a guess.
 *       A bare file name ({@code "config.h"}) must also share at least one folder with the including file,
 *       so that a generated header is not linked to an unrelated file of the same name. For {@code <x.h>}
 *       this step applies only when the path has a folder part, so {@code <string.h>} never matches a
 *       file in the repository by name alone.</li>
 * </ol>
 * A file never resolves to itself. Backslashes are read as slashes, and {@code ..} is followed.
 */
public final class CIncludeResolver {

    private static final Set<String> EXTENSIONS = Set.of(
            "h", "hh", "hpp", "hxx", "h++", "hp", "inl", "ipp", "tpp", "tcc", "inc", "def",
            "c", "cc", "cpp", "cxx", "c++");

    private static final List<String> INCLUDE_DIRS = List.of("include", "inc", "src");

    private final Set<String> paths = new HashSet<>();
    /** Every tail of a path that starts at a folder boundary ("a/b/c.h" gives "c.h", "b/c.h", "a/b/c.h"). */
    private final Map<String, List<String>> byTail = new HashMap<>();

    public CIncludeResolver(Collection<String> allPaths) {
        for (String p : allPaths) {
            if (!isIncludable(p)) continue;
            if (!paths.add(p)) continue;
            int start = 0;
            while (true) {
                byTail.computeIfAbsent(p.substring(start), k -> new ArrayList<>(2)).add(p);
                int slash = p.indexOf('/', start);
                if (slash < 0) break;
                start = slash + 1;
            }
        }
    }

    /** @param include the directive's operand with its delimiters: {@code "x.h"} or {@code <x.h>} */
    public Optional<String> resolve(String fromPath, String include) {
        if (include == null || include.length() < 3) return Optional.empty();
        char open = include.charAt(0);
        if (open != '"' && open != '<') return Optional.empty();
        boolean angle = open == '<';
        String name = include.substring(1, include.length() - 1).trim().replace('\\', '/');
        if (name.isEmpty() || name.startsWith("/")) return Optional.empty(); // absolute: outside the repository

        String hit = searchUpward(fromPath, name, angle);
        if (hit == null) hit = searchByTail(fromPath, name, angle);
        return Optional.ofNullable(hit);
    }

    private String searchUpward(String fromPath, String name, boolean angle) {
        String own = dirOf(fromPath);
        for (String level = own; ; level = dirOf(level)) {
            if (!(angle && level.equals(own))) {
                String hit = lookup(join(level, name), fromPath);
                if (hit != null) return hit;
            }
            for (String dir : INCLUDE_DIRS) {
                String hit = lookup(join(join(level, dir), name), fromPath);
                if (hit != null) return hit;
            }
            if (level.isEmpty()) return null;
        }
    }

    private String searchByTail(String fromPath, String name, boolean angle) {
        String tail = name;
        while (tail.startsWith("./") || tail.startsWith("../")) tail = tail.substring(tail.startsWith("./") ? 2 : 3);
        tail = normalize(tail);
        if (tail == null || tail.isEmpty()) return null;
        boolean bare = tail.indexOf('/') < 0;
        if (angle && bare) return null;

        List<String> candidates = byTail.get(tail);
        if (candidates == null) return null;

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
        if (best == null || tie) return null;
        if (bare && bestShared < 1) return null;
        return best;
    }

    private String lookup(String candidate, String fromPath) {
        if (candidate == null) return null;
        return paths.contains(candidate) && !candidate.equals(fromPath) ? candidate : null;
    }

    /** Joins and resolves "." and ".."; null if the result would leave the repository. */
    private static String join(String dir, String rel) {
        return normalize(dir.isEmpty() ? rel : dir + "/" + rel);
    }

    private static String normalize(String path) {
        String[] parts = path.split("/");
        List<String> out = new ArrayList<>(parts.length);
        for (String part : parts) {
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

    private static boolean isIncludable(String path) {
        String name = path.substring(path.lastIndexOf('/') + 1);
        int dot = name.lastIndexOf('.');
        return dot > 0 && EXTENSIONS.contains(name.substring(dot + 1).toLowerCase(java.util.Locale.ROOT));
    }
}
