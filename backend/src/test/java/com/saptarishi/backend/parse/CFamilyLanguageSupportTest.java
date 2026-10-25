package com.saptarishi.backend.parse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.saptarishi.backend.parse.ParsedFile.RawImport;
import java.util.List;
import org.junit.jupiter.api.Test;

class CFamilyLanguageSupportTest {

    private final CLanguageSupport c = new CLanguageSupport();
    private final CppLanguageSupport cpp = new CppLanguageSupport();

    private static final String C_SAMPLE = """
            #include <stdio.h>
            #include "foo.h"
            #  include "sub/bar.h"
            #include MACRO_HEADER
            #ifdef WIN32
            #include "win.h"
            #endif

            struct Point { int x; };
            enum Color { RED };
            typedef struct { int v; } Node_t;
            typedef struct Named { int v; } Named_t;
            union U { int a; };
            static int helper(void);
            int add(int a, int b) {
                if (a && b) { return 1; }
                for (;;) { break; }
                while (a) { a--; }
                switch (a) { case 1: break; case 2: break; default: break; }
                return a ? b : 0;
            }
            char *dup(const char *s) { return 0; }
            """;

    private static final String CPP_SAMPLE = """
            #include <vector>
            #include "a/b.hpp"
            namespace ns {
            class Foo : public Bar {
            public:
                Foo() {}
                ~Foo() {}
                int n() const { return 1; }
            };
            struct S {};
            enum class E { A };
            template <typename T> class Tpl { };
            template <typename T> T id(T t) { return t; }
            }
            void ns::Foo::m() {
                try { } catch (const std::exception& e) { }
                for (auto x : v) { }
                auto l = [](int a) { return a; };
                if (a and b) { } else if (c) { }
            }
            Foo::Foo(int x) : v(x) {}
            """;

    private static List<String> symbols(ParsedFile f) {
        return f.symbols().stream().map(s -> s.kind() + " " + s.name()).toList();
    }

    @Test
    void cIncludesAreKeptAsWrittenWithTheirDelimiters() {
        ParsedFile f = c.parse("a.c", C_SAMPLE);
        assertTrue(f.parsed());
        assertEquals("c", f.language());
        assertEquals(List.of(
                new RawImport("<stdio.h>", false, false),
                new RawImport("\"foo.h\"", false, false),
                new RawImport("\"sub/bar.h\"", false, false),  // spaces after the # are allowed
                new RawImport("\"win.h\"", false, false)),      // inside #ifdef; the macro include is skipped
                f.imports());
    }

    @Test
    void cSymbolsAreTypesAndFunctionDefinitionsButNotPrototypes() {
        ParsedFile f = c.parse("a.c", C_SAMPLE);
        assertEquals(List.of("CLASS Point", "ENUM Color", "CLASS Node_t", "CLASS Named", "CLASS U",
                "METHOD add", "METHOD dup"), symbols(f));
    }

    @Test
    void cComplexityCountsFunctionsAndDecisionsButNotDefault() {
        // add: 1 + if, &&, for, while, case, case, ?: = 8; dup: 1
        assertEquals(9, c.parse("a.c", C_SAMPLE).complexity());
    }

    @Test
    void cIncludesSurviveBrokenCode() {
        String broken = """
                #include "ok.h"
                MODULE_EXPORT int foo(int x) {
                  if (x) { return 1;
                }
                #include "after.h"
                """;
        ParsedFile f = c.parse("bad.c", broken);
        assertTrue(f.parsed());
        List<String> names = f.imports().stream().map(RawImport::name).toList();
        assertTrue(names.contains("\"ok.h\""));
        assertTrue(names.contains("\"after.h\""));
    }

    @Test
    void cppIncludesAndSymbols() {
        ParsedFile f = cpp.parse("a.cpp", CPP_SAMPLE);
        assertTrue(f.parsed());
        assertEquals("cpp", f.language());
        assertEquals(List.of(new RawImport("<vector>", false, false), new RawImport("\"a/b.hpp\"", false, false)),
                f.imports());
        assertEquals(List.of("CLASS Foo", "METHOD Foo", "METHOD ~Foo", "METHOD n", "CLASS S", "ENUM E",
                "CLASS Tpl", "METHOD id", "METHOD m", "METHOD Foo"), symbols(f));
    }

    @Test
    void cppComplexityCountsLambdasCatchRangeForAndKeywordOperators() {
        // functions: Foo(), ~Foo(), n, id, m, the lambda, Foo::Foo = 7
        // decisions: catch, range-for, "and", if, else-if = 5
        assertEquals(12, cpp.parse("a.cpp", CPP_SAMPLE).complexity());
    }

    @Test
    void cppReadsOperatorsAndPointerReturningFunctions() {
        ParsedFile f = cpp.parse("a.cpp", """
                class A { public: bool operator==(const A& o) const { return true; } };
                char *dup(const char *s) { return nullptr; }
                int &ref() { static int x; return x; }
                """);
        assertEquals(List.of("CLASS A", "METHOD operator==", "METHOD dup", "METHOD ref"), symbols(f));
    }
}
