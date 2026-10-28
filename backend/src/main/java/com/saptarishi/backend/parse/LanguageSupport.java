package com.saptarishi.backend.parse;

import java.util.Set;

public interface LanguageSupport {
    /** Language name, matching SourceFilter (e.g. "java"). */
    String language();

    /** Never throws for bad source: return ParsedFile.unparsed(...) instead. */
    ParsedFile parse(String path, String content);

    /**
     * Whether {@link #parse} fills in {@link ParsedFile#methods()} and {@link ParsedFile#calls()}, so the
     * language has a method-level call graph. The UI shows the call graph only for these languages.
     */
    default boolean extractsCalls() { return false; }
}