package com.saptarishi.backend.parse;

import static org.junit.jupiter.api.Assertions.*;

import com.saptarishi.backend.parse.ParsedFile.CallSite;
import java.util.List;
import org.junit.jupiter.api.Test;

class CallExtractionTest {

    private static final String SRC = """
            package app;
            class Owners {
                private final Repo repo;
                private int hits;
                Owners(Repo repo) { this.repo = repo; }
                void load(int id) {
                    Repo r = new Repo();
                    repo.find(id);
                    r.save(id, 1);
                    Util.trim("x");
                    helper();
                    this.helper();
                    repo.find(id).name();
                    var v = new Helper();
                    v.go();
                }
                void helper() { }
            }
            """;

    private final ParsedFile file = new JavaLanguageSupport().parse("src/app/Owners.java", SRC);

    private List<CallSite> calls(String name) {
        return file.calls().stream().filter(c -> c.name().equals(name)).toList();
    }

    @Test
    void recordsMethodsAndConstructorsWithArity() {
        assertTrue(file.methods().stream().anyMatch(m -> m.type().equals("Owners")
                && m.name().equals("load") && m.arity() == 1 && m.complexity() == 1));
        assertTrue(file.methods().stream().anyMatch(m -> m.name().equals("Owners") && m.arity() == 1));
    }

    @Test
    void receiverTypeComesFromFieldsLocalsAndStaticCalls() {
        assertEquals("Repo", calls("save").get(0).receiver());   // local variable
        assertEquals("Util", calls("trim").get(0).receiver());   // static call
        assertEquals("Helper", calls("go").get(0).receiver());   // var v = new Helper()
        assertTrue(calls("find").stream().allMatch(c -> "Repo".equals(c.receiver()))); // field
    }

    @Test
    void unqualifiedAndThisCallsHaveEmptyReceiverAndChainsAreUnknown() {
        assertEquals(2, calls("helper").size());
        assertTrue(calls("helper").stream().allMatch(c -> c.receiver().isEmpty()));
        assertNull(calls("name").get(0).receiver());
    }

    @Test
    void constructorCallsAreCallSites() {
        CallSite created = calls("Repo").get(0);
        assertEquals("Repo", created.receiver());
        assertEquals(0, created.arity());
        assertEquals("load", created.caller());
    }
}