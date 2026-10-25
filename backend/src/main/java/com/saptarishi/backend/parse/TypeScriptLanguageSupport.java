package com.saptarishi.backend.parse;

import org.springframework.stereotype.Component;

@Component
public class TypeScriptLanguageSupport extends EcmaScriptSupport {
    @Override
    public String language() { return "typescript"; }
}
