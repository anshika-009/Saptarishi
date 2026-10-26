package com.saptarishi.backend.parse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.List;
import org.junit.jupiter.api.Test;

class ParserRegistryTest {

    private final ParserRegistry registry =
            new ParserRegistry(List.of(new PythonLanguageSupport(), new KotlinLanguageSupport()));

    @Test
    void listsItsLanguagesSorted() {
        assertEquals(List.of("kotlin", "python"), registry.languages());
    }

    @Test
    void aLanguageWithoutAParserHasNoPlugin() {
        assertNull(registry.forLanguage("swift"));
    }
}
