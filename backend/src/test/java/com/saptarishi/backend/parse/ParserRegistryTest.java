package com.saptarishi.backend.parse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.List;
import org.junit.jupiter.api.Test;

class ParserRegistryTest {

    private final ParserRegistry registry =
            new ParserRegistry(List.of(new PythonLanguageSupport(), new RubyLanguageSupport()));

    @Test
    void listsItsLanguagesSorted() {
        assertEquals(List.of("python", "ruby"), registry.languages());
    }

    @Test
    void aLanguageWithoutAParserHasNoPlugin() {
        assertNull(registry.forLanguage("swift"));
    }
}
