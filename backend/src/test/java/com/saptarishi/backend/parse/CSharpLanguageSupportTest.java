package com.saptarishi.backend.parse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.saptarishi.backend.parse.ParsedFile.RawImport;
import com.saptarishi.backend.parse.ParsedFile.Symbol;
import java.util.List;
import org.junit.jupiter.api.Test;

class CSharpLanguageSupportTest {

    private final CSharpLanguageSupport cs = new CSharpLanguageSupport();

    private static final String SAMPLE = """
            global using Shared.Core;
            using System;
            using static MyApp.Util.Helpers;
            using Alias = MyApp.Models.User;
            using MyApp.Models;

            namespace MyApp.Services
            {
                public class UserService : IService
                {
                    public UserService() { }
                    public User Find(int id)
                    {
                        if (id > 0 && id < 5) { return null; }
                        foreach (var x in items) { }
                        var y = id > 1 ? 1 : 2;
                        switch (id) { case 1: break; case 2: break; default: break; }
                        var z = id switch { 1 => "a", _ => "b" };
                        Func<int, int> f = a => a + 1;
                        return null;
                    }
                }
                public interface IService { }
                public struct P { }
                public enum E { A }
                public record R(int X);
            }
            """;

    @Test
    void readsUsingsInEveryForm() {
        ParsedFile f = cs.parse("A.cs", SAMPLE);
        assertTrue(f.parsed());
        assertEquals(List.of(
                new RawImport("Shared.Core", false, true),          // global: applies to every file
                new RawImport("System", false, false),
                new RawImport("MyApp.Util.Helpers", true, false),   // using static
                new RawImport("MyApp.Models.User", false, false),   // alias: the target, not the alias name
                new RawImport("MyApp.Models", false, false)), f.imports());
    }

    @Test
    void readsNamespaceAndDeclaredTypes() {
        ParsedFile f = cs.parse("A.cs", SAMPLE);
        assertEquals("MyApp.Services", f.packageName());
        List<String> kinds = f.symbols().stream().map(s -> s.kind() + " " + s.name()).toList();
        assertEquals(List.of("CLASS UserService", "METHOD Find", "INTERFACE IService", "CLASS P", "ENUM E", "RECORD R"), kinds);
        assertEquals(new Symbol("Find", "METHOD", 12), f.symbols().get(1));
    }

    @Test
    void complexityCountsMethodsAndDecisionPoints() {
        // functions: constructor, Find, lambda = 3
        // decisions: if, &&, foreach, ?:, case 1, case 2, switch-expression arm 1 = 7 (default and "_" are free)
        assertEquals(10, cs.parse("A.cs", SAMPLE).complexity());
    }

    @Test
    void fileScopedNamespaceIsRead() {
        assertEquals("A.B", cs.parse("C.cs", "namespace A.B;\n\nclass C { }\n").packageName());
    }

    @Test
    void mentionedNamesAreKept() {
        List<String> refs = cs.parse("A.cs", SAMPLE).typeRefs();
        assertTrue(refs.containsAll(List.of("User", "IService", "UserService")));
        assertFalse(refs.contains("nothing"));
    }

    @Test
    void commentsDoNotCount() {
        String src = "class C {\n // if (a && b) { }\n void M() { }\n}\n";
        assertEquals(1, cs.parse("C.cs", src).complexity());
    }

    @Test
    void positionsSurviveNonAsciiText() {
        String src = "// héllo ✓\nnamespace N;\nclass Ü { void Ä() { } }\n";
        ParsedFile f = cs.parse("U.cs", src);
        assertEquals(List.of(new Symbol("Ü", "CLASS", 3), new Symbol("Ä", "METHOD", 3)), f.symbols());
    }
}
