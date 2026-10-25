package com.saptarishi.backend.graph;

import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Turns a Rust module path ({@code crate::a::b}, {@code self::x}, {@code super::y}, {@code other_crate::z})
 * into the file of the deepest module it names; what is left over is an item inside that module.
 *
 * <p>Layout rules: a crate is a folder with {@code src/lib.rs} or {@code src/main.rs}. Module {@code m} of
 * a file is {@code m.rs} or {@code m/mod.rs} in the file's module folder, which is the folder of
 * {@code lib.rs}, {@code main.rs} and {@code mod.rs}, and a folder named after the file otherwise. A name
 * that is not a module of the current file is looked up as another crate in the repository by folder name
 * ({@code -} read as {@code _}); standard-library and registry crates resolve to nothing.
 */
public final class RustImportResolver {

    private final Set<String> paths;
    private final Map<String, String> rootByCrateDir = new HashMap<>();   // "" or "x/" -> root file
    private final Map<String, String> rootByCrateName = new HashMap<>();
    private final Map<String, String> rootByRootDir = new HashMap<>();    // folder holding the root -> root file
    private final List<String> crateDirs;

    public RustImportResolver(Collection<String> allPaths) {
        this.paths = new HashSet<>(allPaths);
        for (String p : paths) {
            boolean lib = p.equals("src/lib.rs") || p.endsWith("/src/lib.rs");
            boolean main = p.equals("src/main.rs") || p.endsWith("/src/main.rs");
            if (!lib && !main) continue;
            String dir = p.substring(0, p.length() - (lib ? "src/lib.rs" : "src/main.rs").length());
            rootByCrateDir.merge(dir, p, (a, b) -> a.endsWith("lib.rs") ? a : b);
        }
        crateDirs = rootByCrateDir.keySet().stream().sorted(Comparator.comparingInt(String::length).reversed()).toList();
        rootByCrateDir.forEach((dir, root) -> {
            rootByRootDir.put(dirOf(root), root);
            String trimmed = dir.endsWith("/") ? dir.substring(0, dir.length() - 1) : dir;
            String name = trimmed.substring(trimmed.lastIndexOf('/') + 1).replace('-', '_');
            if (!name.isEmpty()) rootByCrateName.putIfAbsent(name, root);
        });
    }

    public Optional<String> resolve(String fromPath, String usePath) {
        String[] seg = usePath.split("::");
        String cur;
        int i = 0;
        switch (seg[0]) {
            case "crate" -> { cur = crateRootOf(fromPath); i = 1; }
            case "self" -> { cur = fromPath; i = 1; }
            case "super" -> {
                cur = fromPath;
                while (cur != null && i < seg.length && seg[i].equals("super")) { cur = parent(cur); i++; }
            }
            default -> {
                cur = child(fromPath, seg[0]);
                if (cur == null) cur = rootByCrateName.get(seg[0]);
                i = 1;
            }
        }
        if (cur == null) return Optional.empty();
        for (; i < seg.length; i++) {
            String next = child(cur, seg[i]);
            if (next == null) break;
            cur = next;
        }
        return cur.equals(fromPath) ? Optional.empty() : Optional.of(cur);
    }

    private String crateRootOf(String file) {
        for (String dir : crateDirs) if (file.startsWith(dir + "src/")) return rootByCrateDir.get(dir);
        return null;
    }

    private String child(String file, String name) {
        String dir = moduleDir(file);
        String base = dir.isEmpty() ? name : dir + "/" + name;
        if (paths.contains(base + ".rs")) return base + ".rs";
        if (paths.contains(base + "/mod.rs")) return base + "/mod.rs";
        return null;
    }

    /** The module that contains this file's module, or null for a crate root. */
    private String parent(String file) {
        if (file.equals(rootByRootDir.get(dirOf(file))) ) return null;
        String parentDir = dirOf(moduleDir(file));
        String root = rootByRootDir.get(parentDir);
        if (root != null) return root;
        String modRs = parentDir.isEmpty() ? "mod.rs" : parentDir + "/mod.rs";
        if (paths.contains(modRs)) return modRs;
        String sibling = parentDir + ".rs";
        return paths.contains(sibling) ? sibling : null;
    }

    private static String moduleDir(String file) {
        String name = file.substring(file.lastIndexOf('/') + 1);
        String dir = dirOf(file);
        if (name.equals("lib.rs") || name.equals("main.rs") || name.equals("mod.rs")) return dir;
        String stem = name.substring(0, name.length() - 3);
        return dir.isEmpty() ? stem : dir + "/" + stem;
    }

    private static String dirOf(String path) {
        int slash = path.lastIndexOf('/');
        return slash < 0 ? "" : path.substring(0, slash);
    }
}
