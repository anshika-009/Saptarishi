package com.saptarishi.backend.parse;

import org.springframework.stereotype.Component;
import org.treesitter.TSLanguage;
import org.treesitter.TreeSitterJavascript;

/**
 * JavaScript, including JSX, which the JavaScript grammar reads itself. Covers .js, .jsx, .mjs and .cjs.
 * The shared work is in {@link TreeSitterEcmaSupport}.
 */
@Component
public class JavaScriptLanguageSupport extends TreeSitterEcmaSupport {

    @Override
    public String language() { return "javascript"; }

    @Override
    protected TSLanguage grammar() { return new TreeSitterJavascript(); }
}