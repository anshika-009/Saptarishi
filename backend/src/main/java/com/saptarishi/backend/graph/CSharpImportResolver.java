package com.saptarishi.backend.graph;

import com.saptarishi.backend.parse.ParsedFile;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Links C# files. A {@code using} names a namespace, which is not a file, so a file is linked to those files
 * of the namespace that declare a type it mentions (the same idea as Java's same-package references). Types of
 * the file's own namespace and of the namespaces around it need no {@code using}, so those are linked too,
 * as references. A {@code global using} anywhere applies to every file. An alias or {@code using static}
 * names a type, so when the name is not a namespace the ones around it are tried.
 *
 * <p>The same idea fits any language where a file declares a namespace or package and imports name them or
 * a type in them, so it also links PHP (pass the language; PHP's {@code \} is read as a dot).
 */
public final class CSharpImportResolver {

    private static final Set<String> TYPE_KINDS = Set.of("CLASS", "INTERFACE", "ENUM", "RECORD");

    /** namespace -> type name -> files declaring it (several for a partial class) */
    private final Map<String, Map<String, List<String>>> types = new HashMap<>();
    private final Set<String> globalUsings = new LinkedHashSet<>();

    public CSharpImportResolver(Collection<ParsedFile> files) {
        this(files, "csharp");
    }

    public CSharpImportResolver(Collection<ParsedFile> files, String language) {
        for (ParsedFile f : files) {
            if (!language.equals(f.language()) || !f.parsed()) continue;
            for (ParsedFile.Symbol s : f.symbols()) {
                if (TYPE_KINDS.contains(s.kind())) {
                    types.computeIfAbsent(f.packageName(), k -> new HashMap<>())
                            .computeIfAbsent(s.name(), k -> new ArrayList<>()).add(f.path());
                }
            }
            for (ParsedFile.RawImport i : f.imports()) if (i.isWildcard()) globalUsings.add(i.name());
        }
    }

    /** Files reached through a using (including global ones). */
    public Set<String> resolve(ParsedFile f) {
        Set<String> mentioned = new HashSet<>(f.typeRefs());
        Set<String> out = new LinkedHashSet<>();
        List<String> names = new ArrayList<>(globalUsings);
        for (ParsedFile.RawImport i : f.imports()) names.add(i.name());
        for (String name : names) {
            String ns = name;
            while (ns != null && !types.containsKey(ns)) ns = parent(ns);
            if (ns != null) collect(ns, mentioned, f.path(), out);
        }
        return out;
    }

    /** Files of the file's own namespace and the namespaces around it that it mentions. */
    public Set<String> resolveReferences(ParsedFile f) {
        Set<String> mentioned = new HashSet<>(f.typeRefs());
        Set<String> out = new LinkedHashSet<>();
        for (String ns = f.packageName(); ns != null; ns = parent(ns)) collect(ns, mentioned, f.path(), out);
        return out;
    }

    private void collect(String ns, Set<String> mentioned, String self, Set<String> out) {
        Map<String, List<String>> inNamespace = types.get(ns);
        if (inNamespace == null) return;
        for (Map.Entry<String, List<String>> e : inNamespace.entrySet()) {
            if (!mentioned.contains(e.getKey())) continue;
            for (String path : e.getValue()) if (!path.equals(self)) out.add(path);
        }
    }

    private static String parent(String ns) {
        int dot = ns.lastIndexOf('.');
        return dot < 0 ? null : ns.substring(0, dot);
    }
}
