package com.saptarishi.backend.parse;

import org.springframework.stereotype.Component;
import org.treesitter.TSLanguage;
import org.treesitter.TreeSitterTsx;
import org.treesitter.TreeSitterTypescript;

/**
 * TypeScript. The plain TypeScript grammar cannot read JSX, so a .tsx file uses the TSX grammar; every
 * other file uses the TypeScript one. Both are reported as language "typescript". The shared work is
 * in {@link TreeSitterEcmaSupport}.
 */
@Component
public class TypeScriptLanguageSupport extends TreeSitterEcmaSupport {

    @Override
    public String language() { return "typescript"; }

    @Override
    protected TSLanguage grammar() { return new TreeSitterTypescript(); }

    @Override
    protected TSLanguage grammar(String path) {
        return path.toLowerCase().endsWith(".tsx") ? new TreeSitterTsx() : grammar();
    }
}