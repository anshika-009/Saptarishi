package com.saptarishi.backend.ingest;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class GithubUrlTest {

    @Test void parsesPlainUrl() {
        assertEquals(new GithubUrl("google", "gson"), GithubUrl.parse("https://github.com/google/gson"));
    }
    @Test void stripsGitSuffixAndExtraPath() {
        assertEquals(new GithubUrl("google", "gson"), GithubUrl.parse("https://github.com/google/gson.git"));
        assertEquals(new GithubUrl("google", "gson"), GithubUrl.parse("github.com/google/gson/tree/main/gson"));
    }
    @Test void rejectsOtherHosts() {
        assertThrows(IllegalArgumentException.class, () -> GithubUrl.parse("https://evil.com/a/b"));
        assertThrows(IllegalArgumentException.class, () -> GithubUrl.parse("https://github.com.evil.com/a/b"));
        assertThrows(IllegalArgumentException.class, () -> GithubUrl.parse("https://github.com@evil.com/a/b"));
    }
    @Test void rejectsBadInput() {
        assertThrows(IllegalArgumentException.class, () -> GithubUrl.parse(""));
        assertThrows(IllegalArgumentException.class, () -> GithubUrl.parse("https://github.com/onlyowner"));
        assertThrows(IllegalArgumentException.class, () -> GithubUrl.parse("https://github.com/a/.."));
        assertThrows(IllegalArgumentException.class, () -> GithubUrl.parse("file:///etc/passwd"));
    }
}