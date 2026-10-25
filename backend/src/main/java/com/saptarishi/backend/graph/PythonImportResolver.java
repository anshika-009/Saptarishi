package com.saptarishi.backend.graph;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Turns a dotted Python module name into the repository file it names, if any.
 *
 * <p>Relative imports ({@code .a}, {@code ..b.c}, {@code .}) start from the importing file's package.
 * Absolute imports are tried from every directory above the importing file (a script can import its
 * neighbours, a package can be imported from the folder that contains it) and from {@code src/} and
 * {@code lib/}. A module is {@code x/y.py} or the package {@code x/y/__init__.py}. Anything that is not
 * a file in the repository (standard library, installed packages) resolves to nothing.
 */
public final class PythonImportResolver {

    private static final List<String> EXTRA_ROOTS = List.of("src", "lib");

    private final Set<String> paths;

    public PythonImportResolver(Collection<String> allPaths) {
        this.paths = new HashSet<>(allPaths);
    }

    public Optional<String> resolve(String fromPath, String module) {
        if (module.isEmpty()) return Optional.empty();
        Optional<String> hit = module.startsWith(".") ? relative(fromPath, module) : absolute(fromPath, module);
        return hit.filter(p -> !p.equals(fromPath));
    }

    private Optional<String> relative(String fromPath, String module) {
        int dots = 0;
        while (dots < module.length() && module.charAt(dots) == '.') dots++;
        String dir = ScriptImportResolver.dirOf(fromPath);
        for (int i = 1; i < dots; i++) {
            if (dir.isEmpty()) return Optional.empty();
            dir = ScriptImportResolver.dirOf(dir);
        }
        String rest = module.substring(dots).replace('.', '/');
        if (rest.isEmpty()) return probe(dir, true);
        return probe(dir.isEmpty() ? rest : dir + "/" + rest, false);
    }

    private Optional<String> absolute(String fromPath, String module) {
        String asPath = module.replace('.', '/');
        Set<String> roots = new LinkedHashSet<>();
        for (String dir = ScriptImportResolver.dirOf(fromPath); ; dir = ScriptImportResolver.dirOf(dir)) {
            roots.add(dir);
            if (dir.isEmpty()) break;
        }
        roots.addAll(EXTRA_ROOTS);
        List<String> candidates = new ArrayList<>(roots.size());
        for (String root : roots) candidates.add(root.isEmpty() ? asPath : root + "/" + asPath);
        for (String base : candidates) {
            Optional<String> hit = probe(base, false);
            if (hit.isPresent()) return hit;
        }
        return Optional.empty();
    }

    /** packageDir=true: base is a folder and only its __init__.py counts. */
    private Optional<String> probe(String base, boolean packageDir) {
        String init = base.isEmpty() ? "__init__.py" : base + "/__init__.py";
        if (packageDir) return paths.contains(init) ? Optional.of(init) : Optional.empty();
        if (paths.contains(base + ".py")) return Optional.of(base + ".py");
        return paths.contains(init) ? Optional.of(init) : Optional.empty();
    }
}
