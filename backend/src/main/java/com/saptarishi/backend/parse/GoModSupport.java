package com.saptarishi.backend.parse;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * Reads a go.mod file for its module path, which is what Go import paths start with. The path is kept
 * in {@code packageName}. The file is not source code: it adds no edges and is left out of the graph.
 */
@Component
public class GoModSupport implements LanguageSupport {

    public static final String LANGUAGE = "gomod";

    private static final Pattern MODULE = Pattern.compile("^[ \\t]*module[ \\t]+\"?([^\\s\"]+)\"?", Pattern.MULTILINE);

    @Override
    public String language() { return LANGUAGE; }

    @Override
    public ParsedFile parse(String path, String content) {
        Matcher m = MODULE.matcher(content);
        String module = m.find() ? m.group(1) : "";
        return new ParsedFile(path, LANGUAGE, module, List.of(), List.of(), 0, 0, 0, true);
    }
}
