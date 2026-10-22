package com.saptarishi.backend.graph;

import com.saptarishi.backend.parse.ParsedFile;
import com.saptarishi.backend.parse.ParsedFile.RawImport;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Turns Java import text into file paths. Built once per repo, after every file is parsed,
 * because it needs the full index of classes and packages.
 */
public class JavaImportResolver {

    private final Map<String, String> classToPath = new HashMap<>();          // com.app.Foo -> src/.../Foo.java
    private final Map<String, List<String>> packageToPaths = new HashMap<>(); // com.app -> [files in it]

    public JavaImportResolver(List<ParsedFile> files) {
        for (ParsedFile f : files) {
            if (!"java".equals(f.language()) || !f.parsed()) continue;
            String base = baseName(f.path());
            String fqcn = f.packageName().isEmpty() ? base : f.packageName() + "." + base;
            classToPath.putIfAbsent(fqcn, f.path());
            packageToPaths.computeIfAbsent(f.packageName(), k -> new ArrayList<>()).add(f.path());
        }
    }

    /** Paths this file imports from inside the repo. External libraries (java.util.List, ...) are ignored. */
    public Set<String> resolve(ParsedFile file) {
        Set<String> targets = new LinkedHashSet<>();
        for (RawImport imp : file.imports()) {
            for (String t : resolveOne(imp)) {
                if (!t.equals(file.path())) targets.add(t); // no self-edges
            }
        }
        return targets;
    }

    /**
     * Same-package dependencies: Java needs no import for these. A type name the file uses
     * matches a class in its own package, unless an explicit import of that simple name shadows it.
     */
    public Set<String> resolveReferences(ParsedFile file) {
        Set<String> targets = new LinkedHashSet<>();
        if (file.typeRefs().isEmpty()) return targets;

        Set<String> shadowed = new HashSet<>();
        for (RawImport imp : file.imports()) {
            if (!imp.isStatic() && !imp.isWildcard()) {
                String n = imp.name();
                shadowed.add(n.substring(n.lastIndexOf('.') + 1));
            }
        }
        String prefix = file.packageName().isEmpty() ? "" : file.packageName() + ".";
        for (String name : file.typeRefs()) {
            if (shadowed.contains(name)) continue;
            String p = classToPath.get(prefix + name);
            if (p != null && !p.equals(file.path())) targets.add(p);
        }
        return targets;
    }

    private List<String> resolveOne(RawImport imp) {
        String name = imp.name();

        if (imp.isWildcard()) {
            // import com.app.*;  -> every file in that package
            if (!imp.isStatic()) {
                List<String> pkg = packageToPaths.get(name);
                if (pkg != null) return pkg;
            }
            // import static com.app.Util.*;  or  import com.app.Outer.*;  -> the class itself
            String p = classPath(name);
            return p == null ? List.of() : List.of(p);
        }

        // import static com.app.Util.trim;  -> drop the member name, keep the class
        String className = imp.isStatic() ? stripLast(name) : name;
        String p = className == null ? null : classPath(className);
        return p == null ? List.of() : List.of(p);
    }

    /** Tries com.app.Outer.Inner, then com.app.Outer, so nested-class imports land on the outer file. */
    private String classPath(String name) {
        String n = name;
        while (n != null) {
            String p = classToPath.get(n);
            if (p != null) return p;
            n = stripLast(n);
        }
        return null;
    }

    private static String stripLast(String s) {
        int dot = s.lastIndexOf('.');
        return dot < 0 ? null : s.substring(0, dot);
    }

    private static String baseName(String path) {
        String file = path.substring(path.lastIndexOf('/') + 1);
        return file.endsWith(".java") ? file.substring(0, file.length() - 5) : file;
    }
}