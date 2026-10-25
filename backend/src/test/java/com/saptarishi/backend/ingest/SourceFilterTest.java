package com.saptarishi.backend.ingest;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.junit.jupiter.api.Test;

class SourceFilterTest {

    private final SourceFilter filter = new SourceFilter();

    @Test
    void cAndCppExtensionsAreParsedLanguagesNotPlainText() {
        assertEquals("c", filter.classify("src/main.c"));
        assertEquals("c", filter.classify("include/util.h"));
        for (String ext : List.of("cpp", "cc", "cxx", "c++", "hpp", "hh", "hxx", "h++", "ipp", "tpp", "inl")) {
            assertEquals("cpp", filter.classify("src/x." + ext), ext);
        }
        assertEquals("cpp", filter.classify("SRC/X.CPP"));
    }

    @Test
    void headersStayCInARepositoryWithoutCppSources() {
        List<IndexedFile> files = List.of(
                new IndexedFile("a.c", "c", 1), new IndexedFile("a.h", "c", 1), new IndexedFile("README.md", "text", 1));
        assertEquals(files, filter.assignHeaderLanguage(files));
    }

    @Test
    void aCLibraryWithOneCppExampleKeepsItsHeadersAsC() {
        List<IndexedFile> files = List.of(
                new IndexedFile("a.c", "c", 1), new IndexedFile("b.c", "c", 1), new IndexedFile("c.c", "c", 1),
                new IndexedFile("a.h", "c", 1), new IndexedFile("examples/qt.cpp", "cpp", 1));
        assertEquals(files, filter.assignHeaderLanguage(files));
    }

    @Test
    void headersBecomeCppWhenCppFilesAreAtLeastAsManyAsCSources() {
        List<IndexedFile> files = List.of(
                new IndexedFile("a.c", "c", 1), new IndexedFile("a.h", "c", 2), new IndexedFile("b.cpp", "cpp", 3),
                new IndexedFile("b.hpp", "cpp", 4), new IndexedFile("README.md", "text", 5));
        assertEquals(List.of(
                new IndexedFile("a.c", "c", 1), new IndexedFile("a.h", "cpp", 2), new IndexedFile("b.cpp", "cpp", 3),
                new IndexedFile("b.hpp", "cpp", 4), new IndexedFile("README.md", "text", 5)),
                filter.assignHeaderLanguage(files));
    }
}
