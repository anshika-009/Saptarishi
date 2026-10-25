package com.saptarishi.backend.parse;

import java.util.Set;
import org.springframework.stereotype.Component;
import org.treesitter.TSLanguage;
import org.treesitter.TreeSitterC;

/** C ({@code .c}, and {@code .h} in a repository with no C++ sources). See {@link CFamilySupport}. */
@Component
public class CLanguageSupport extends CFamilySupport {

    @Override
    public String language() { return "c"; }

    @Override
    protected TSLanguage grammar() { return new TreeSitterC(); }

    @Override
    protected Set<String> functionTypes() { return FUNCTIONS; }

    @Override
    protected Set<String> decisionTypes() { return DECISIONS; }
}
