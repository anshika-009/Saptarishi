package com.saptarishi.backend.parse;

import java.util.List;

/** Everything downstream (resolution, graph, search) knows about a file. */
public record ParsedFile(
        String path,
        String language,
        String packageName,          // "" if none or not applicable
        List<RawImport> imports,
        List<Symbol> symbols,
        int tokens,
        int complexity,
        int loc,                     // non-blank lines
        boolean parsed,              // false = broken file, kept as a plain node
        List<String> typeRefs) {     // simple type names this file uses (Java: for same-package edges)

    /** Old shape, so existing callers and tests keep compiling. */
    public ParsedFile(String path, String language, String packageName, List<RawImport> imports,
                      List<Symbol> symbols, int tokens, int complexity, int loc, boolean parsed) {
        this(path, language, packageName, imports, symbols, tokens, complexity, loc, parsed, List.of());
    }

    public record RawImport(String name, boolean isStatic, boolean isWildcard) {}

    /** kind: CLASS, INTERFACE, ENUM, RECORD, METHOD, CONSTRUCTOR */
    public record Symbol(String name, String kind, int line) {}

    public static ParsedFile unparsed(String path, String language, int loc) {
        return new ParsedFile(path, language, "", List.of(), List.of(), 0, 0, loc, false);
    }
}