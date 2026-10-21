package com.saptarishi.backend.parse;

import java.util.Set;

public interface LanguageSupport {
    /** Language name, matching SourceFilter (e.g. "java"). */
    String language();

    /** Never throws for bad source: return ParsedFile.unparsed(...) instead. */
    ParsedFile parse(String path, String content);
}