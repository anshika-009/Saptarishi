package com.saptarishi.backend.graph;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Turns an address found in an HTML file ({@code <script src>}, {@code <link href>}, {@code <iframe src>})
 * into the repository file it names, if any.
 *
 * <p>It follows the rules of a browser, not of a module system: an address without a leading slash is
 * relative to the page, so {@code app.js} means the file next to it ({@code ScriptImportResolver} would
 * look for it at the repository root). A leading slash means the site root. The site root is not known, so
 * it is taken to be one of the folders above the page, nearest first, and then a common web folder
 * ({@code public}, {@code static}, {@code src}, {@code www}) at the repository root: a Vite page's
 * {@code <script src="/src/main.jsx">} resolves at the root, a page in {@code docs/} that loads
 * {@code /assets/site.js} resolves to {@code docs/assets/site.js}.
 *
 * <p>The query and fragment are dropped and percent escapes are decoded. A folder address
 * ({@code about/}) means its {@code index.html}, and an address without an extension may be a page served
 * without it ({@code about} for {@code about.html}). Anything that is not a file in the repository (a CDN,
 * an image that was not kept) resolves to nothing.
 */
public final class HtmlImportResolver {

    private static final List<String> WEB_ROOTS = List.of("public", "static", "src", "www");
    private static final List<String> INDEXES = List.of("index.html", "index.htm");

    private final Set<String> paths;

    public HtmlImportResolver(Collection<String> allPaths) {
        this.paths = new HashSet<>(allPaths);
    }

    public Optional<String> resolve(String fromPath, String address) {
        String ref = address.trim();
        int cut = indexOfAny(ref, '?', '#');
        if (cut >= 0) ref = ref.substring(0, cut);
        ref = decode(ref);
        if (ref.isEmpty()) return Optional.empty();

        String dir = dirOf(fromPath);
        List<String> bases = new ArrayList<>();
        if (ref.startsWith("/")) {
            String rooted = ref.substring(1);
            for (String d = dir; ; d = dirOf(d)) {
                bases.add(d.isEmpty() ? rooted : d + "/" + rooted);
                if (d.isEmpty()) break;
            }
            for (String root : WEB_ROOTS) bases.add(root + "/" + rooted);
        } else {
            bases.add(dir.isEmpty() ? ref : dir + "/" + ref);
        }

        for (String base : bases) {
            String normal = normalize(base);
            if (normal == null) continue;
            Optional<String> hit = probe(normal);
            if (hit.isPresent() && !hit.get().equals(fromPath)) return hit;
        }
        return Optional.empty();
    }

    private Optional<String> probe(String path) {
        boolean folder = path.endsWith("/");
        String base = folder ? path.substring(0, path.length() - 1) : path;
        if (!folder && paths.contains(base)) return Optional.of(base);
        for (String index : INDEXES) {
            String candidate = base.isEmpty() ? index : base + "/" + index;
            if (paths.contains(candidate)) return Optional.of(candidate);
        }
        String last = base.substring(base.lastIndexOf('/') + 1);
        if (!folder && !base.isEmpty() && last.indexOf('.') < 0 && paths.contains(base + ".html")) {
            return Optional.of(base + ".html");
        }
        return Optional.empty();
    }

    /** Resolves "." and ".." and repeated slashes; null if it climbs out of the repository. Keeps a trailing slash. */
    private static String normalize(String path) {
        boolean folder = path.endsWith("/");
        List<String> out = new ArrayList<>();
        for (String seg : path.split("/")) {
            if (seg.isEmpty() || seg.equals(".")) continue;
            if (seg.equals("..")) {
                if (out.isEmpty()) return null;
                out.remove(out.size() - 1);
            } else {
                out.add(seg);
            }
        }
        String joined = String.join("/", out);
        return folder && !joined.isEmpty() ? joined + "/" : joined;
    }

    private static String dirOf(String path) {
        int slash = path.lastIndexOf('/');
        return slash < 0 ? "" : path.substring(0, slash);
    }

    private static String decode(String s) {
        try {
            return URLDecoder.decode(s.replace("+", "%2B"), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            return s; // a stray % that is not an escape
        }
    }

    private static int indexOfAny(String s, char a, char b) {
        int i = s.indexOf(a), j = s.indexOf(b);
        return i < 0 ? j : j < 0 ? i : Math.min(i, j);
    }
}