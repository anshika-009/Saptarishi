package com.saptarishi.backend.parse;

import org.springframework.stereotype.Component;

@Component
public class JavaScriptLanguageSupport extends EcmaScriptSupport {
    @Override
    public String language() { return "javascript"; }
}
