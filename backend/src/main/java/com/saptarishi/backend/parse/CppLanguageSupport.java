package com.saptarishi.backend.parse;

import java.util.HashSet;
import java.util.Set;
import org.springframework.stereotype.Component;
import org.treesitter.TSLanguage;
import org.treesitter.TreeSitterCpp;

/**
 * C++ ({@code .cpp}, {@code .cc}, {@code .hpp} and the like, and {@code .h} in a repository that has C++
 * sources). On top of {@link CFamilySupport}, a lambda counts as a function, and a range-based {@code for},
 * a {@code catch} and the keyword forms {@code and} / {@code or} count as decision points.
 */
@Component
public class CppLanguageSupport extends CFamilySupport {

    private static final Set<String> CPP_FUNCTIONS = withExtra(FUNCTIONS, "lambda_expression");
    private static final Set<String> CPP_DECISIONS = withExtra(DECISIONS, "for_range_loop", "catch_clause", "and", "or");

    @Override
    public String language() { return "cpp"; }

    @Override
    protected TSLanguage grammar() { return new TreeSitterCpp(); }

    @Override
    protected Set<String> functionTypes() { return CPP_FUNCTIONS; }

    @Override
    protected Set<String> decisionTypes() { return CPP_DECISIONS; }

    private static Set<String> withExtra(Set<String> base, String... extra) {
        Set<String> all = new HashSet<>(base);
        all.addAll(Set.of(extra));
        return Set.copyOf(all);
    }
}
