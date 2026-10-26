package com.saptarishi.backend.parse;

import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/** Finds the plugin for a language. Spring injects every LanguageSupport bean automatically. */
@Component
public class ParserRegistry {

    private final Map<String, LanguageSupport> byLanguage;

    public ParserRegistry(List<LanguageSupport> plugins) {
        this.byLanguage = plugins.stream()
                .collect(Collectors.toMap(LanguageSupport::language, Function.identity()));
    }

    /** Returns null if there is no parser for this language (the file stays a plain node). */
    public LanguageSupport forLanguage(String language) {
        return byLanguage.get(language);
    }

    /** The languages that have a parser, sorted. The UI uses this to tell analysed files from plain ones. */
    public List<String> languages() {
        return List.copyOf(new TreeSet<>(byLanguage.keySet()));
    }
}