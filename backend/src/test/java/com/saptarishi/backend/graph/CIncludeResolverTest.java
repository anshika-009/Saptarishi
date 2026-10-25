package com.saptarishi.backend.graph;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class CIncludeResolverTest {

    private static final List<String> PATHS = List.of(
            "src/main.c",
            "src/util.h",
            "src/net/socket.c",
            "src/net/socket.h",
            "include/mylib/api.h",
            "lib/foo/include/foo/bar.h",
            "lib/foo/src/bar.c",
            "app/config.h",
            "app/main.cpp",
            "tests/fixtures/config.h",
            "libA/pub/sub/q.h",
            "libB/pub/sub/q.h",
            "a/dup.h",
            "b/dup.h",
            "README.md");

    private final CIncludeResolver r = new CIncludeResolver(PATHS);

    private Optional<String> from(String file, String include) { return r.resolve(file, include); }

    @Test
    void quotedIncludeLooksInTheIncludingFilesFolderFirst() {
        assertEquals(Optional.of("src/util.h"), from("src/main.c", "\"util.h\""));
        assertEquals(Optional.of("src/net/socket.h"), from("src/net/socket.c", "\"socket.h\""));
    }

    @Test
    void relativeSubfolderAndParentSegments() {
        assertEquals(Optional.of("src/net/socket.h"), from("src/main.c", "\"net/socket.h\""));
        assertEquals(Optional.of("src/util.h"), from("src/net/socket.c", "\"../util.h\""));
        assertEquals(Optional.of("src/util.h"), from("src/main.c", "\"./net/../util.h\""));
    }

    @Test
    void includeFolderAtTheRootIsSearchedForBothForms() {
        assertEquals(Optional.of("include/mylib/api.h"), from("src/net/socket.c", "<mylib/api.h>"));
        assertEquals(Optional.of("include/mylib/api.h"), from("src/net/socket.c", "\"mylib/api.h\""));
    }

    @Test
    void includeFolderOfTheNearestLibraryComesFromWalkingUp() {
        assertEquals(Optional.of("lib/foo/include/foo/bar.h"), from("lib/foo/src/bar.c", "\"foo/bar.h\""));
    }

    @Test
    void aSrcFolderHigherUpIsSearchedToo() {
        assertEquals(Optional.of("src/net/socket.h"), from("app/main.cpp", "\"net/socket.h\""));
    }

    @Test
    void anUnambiguousPathTailIsFoundFromAnywhere() {
        assertEquals(Optional.of("lib/foo/include/foo/bar.h"), from("app/main.cpp", "<foo/bar.h>"));
    }

    @Test
    void systemHeadersResolveToNothing() {
        assertEquals(Optional.empty(), from("src/main.c", "<stdio.h>"));
        assertEquals(Optional.empty(), from("app/main.cpp", "<vector>"));
        assertEquals(Optional.empty(), from("app/main.cpp", "<sys/types.h>"));
    }

    @Test
    void angleIncludeDoesNotLookInTheIncludingFilesOwnFolder() {
        assertEquals(Optional.of("app/config.h"), from("app/main.cpp", "\"config.h\""));
        assertEquals(Optional.empty(), from("app/main.cpp", "<config.h>"));
    }

    @Test
    void aBareNameFoundOnlyByTailNeedsAFolderInCommonWithTheIncluder() {
        // api.h exists once, but far from tools/: more likely a generated or system header
        assertEquals(Optional.empty(), from("tools/gen.c", "\"api.h\""));
        // config.h exists twice and neither is near
        assertEquals(Optional.empty(), from("tools/gen.c", "\"config.h\""));
    }

    @Test
    void anAmbiguousTailIsNotGuessed() {
        assertEquals(Optional.empty(), from("tools/gen.c", "\"pub/sub/q.h\""));
        assertEquals(Optional.empty(), from("x/y.c", "\"dup.h\""));
    }

    @Test
    void theNearestOfSeveralTailMatchesWins() {
        assertEquals(Optional.of("libA/pub/sub/q.h"), from("libA/src/m.c", "\"sub/q.h\""));
        assertEquals(Optional.of("libB/pub/sub/q.h"), from("libB/src/m.c", "\"sub/q.h\""));
    }

    @Test
    void aFileNeverResolvesToItself() {
        assertEquals(Optional.empty(), from("src/util.h", "\"util.h\""));
    }

    @Test
    void backslashesAreReadAsSlashes() {
        assertEquals(Optional.of("src/net/socket.h"), from("src/main.c", "\"net\\socket.h\""));
    }

    @Test
    void unusableIncludesGiveNothing() {
        assertEquals(Optional.empty(), from("src/main.c", "\"/usr/include/x.h\""));
        assertEquals(Optional.empty(), from("src/main.c", "MACRO_HEADER"));
        assertEquals(Optional.empty(), from("src/main.c", "\"\""));
        assertEquals(Optional.empty(), from("src/main.c", ""));
        assertEquals(Optional.empty(), from("src/main.c", null));
        assertEquals(Optional.empty(), from("src/main.c", "\"../../../x.h\""));
    }

    @Test
    void onlyHeaderAndSourceFilesAreTargets() {
        assertEquals(Optional.empty(), from("src/main.c", "\"../README.md\""));
    }
}
