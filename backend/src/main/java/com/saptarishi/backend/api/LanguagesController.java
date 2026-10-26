package com.saptarishi.backend.api;

import com.saptarishi.backend.parse.ParserRegistry;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Which languages are analysed (have a parser). Files in any other language stay plain nodes. */
@RestController
@RequestMapping("/api/languages")
public class LanguagesController {

    private final ParserRegistry registry;

    public LanguagesController(ParserRegistry registry) {
        this.registry = registry;
    }

    @GetMapping
    public Map<String, List<String>> get() {
        return Map.of("analysed", registry.languages());
    }
}
