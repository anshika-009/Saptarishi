package com.saptarishi.backend.graph;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Turns a JavaScript/TypeScript import specifier into the repository file it names, if any.
 *
 * <p>Handles relative paths ({@code ./a}, {@code ../b/c}), missing extensions, index files, TypeScript's
 * habit of writing {@code ./x.js} for {@code x.ts}, and the two common "root" conventions: a leading
 * {@code @/} or {@code ~/} meaning {@code src/}, and bare paths that start at the repository root or
 * {@code src/} (a configured baseUrl). Anything that does not match a file in the repository (npm
 * packages, node built-ins) resolves to nothing. Path aliases defined in tsconfig/webpack are not read.
 */
public final class ScriptImportResolver {

    private static final List<String> EXTENSIONS = List.of(".ts", ".tsx", ".js", ".jsx", ".mjs", ".cjs", ".json");
    private static final List<String> JS_EXTENSIONS = List.of(".js", ".jsx", ".mjs", ".cjs");

    private final Set<String> paths;

    public ScriptImportResolver(Collection<String> allPaths) {
        this.paths = new HashSet<>(allPaths);
    }

    public Optional<String> resolve(String fromPath, String specifier) {
        String spec = specifier;
        int cut = indexOfAny(spec, '?', '#');
        if (cut >= 0) spec = spec.substring(0, cut);
        if (spec.isEmpty()) return Optional.empty();

        List<String> bases = new ArrayList<>(2);
        if (spec.startsWith("./") || spec.startsWith("../") || spec.equals(".") || spec.equals("..")) {
            bases.add(dirOf(fromPath).isEmpty() ? spec : dirOf(fromPath) + "/" + spec);
        } else if (spec.startsWith("/")) {
            bases.add(spec.substring(1));
        } else if (spec.startsWith("@/") || spec.startsWith("~/")) {
            bases.add("src/" + spec.substring(2));
            bases.add(spec.substring(2));
        } else {
            bases.add(spec);
            bases.add("src/" + spec);
        }
        for (String base : bases) {
            String normal = normalize(base);
            if (normal == null) continue;
            Optional<String> hit = probe(normal);
            if (hit.isPresent() && !hit.get().equals(fromPath)) return hit;
        }
        return Optional.empty();
    }

    private Optional<String> probe(String base) {
        if (paths.contains(base) && hasExtension(base)) return Optional.of(base);
        for (String js : JS_EXTENSIONS) {
            if (base.endsWith(js)) { // "./x.js" may mean x.ts
                String stem = base.substring(0, base.length() - js.length());
                for (String ext : List.of(".ts", ".tsx")) if (paths.contains(stem + ext)) return Optional.of(stem + ext);
            }
        }
        for (String ext : EXTENSIONS) if (paths.contains(base + ext)) return Optional.of(base + ext);
        for (String ext : EXTENSIONS) if (paths.contains(base + "/index" + ext)) return Optional.of(base + "/index" + ext);
        return Optional.empty();
    }

    private static boolean hasExtension(String p) {
        int slash = p.lastIndexOf('/');
        return p.indexOf('.', slash + 1) > slash + 1;
    }

    /** Resolves "." and ".." segments; null if the path climbs out of the repository. */
    static String normalize(String path) {
        Deque<String> stack = new ArrayDeque<>();
        for (String seg : path.split("/")) {
            if (seg.isEmpty() || seg.equals(".")) continue;
            if (seg.equals("..")) {
                if (stack.isEmpty()) return null;
                stack.removeLast();
            } else {
                stack.addLast(seg);
            }
        }
        return stack.isEmpty() ? null : String.join("/", stack);
    }

    static String dirOf(String path) {
        int slash = path.lastIndexOf('/');
        return slash < 0 ? "" : path.substring(0, slash);
    }

    private static int indexOfAny(String s, char a, char b) {
        int i = s.indexOf(a);
        int j = s.indexOf(b);
        return i < 0 ? j : j < 0 ? i : Math.min(i, j);
    }
}
