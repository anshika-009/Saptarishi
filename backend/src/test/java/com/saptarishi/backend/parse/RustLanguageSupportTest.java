package com.saptarishi.backend.parse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.saptarishi.backend.parse.ParsedFile.RawImport;
import java.util.List;
import org.junit.jupiter.api.Test;

class RustLanguageSupportTest {

    private final RustLanguageSupport rust = new RustLanguageSupport();

    private static final String SAMPLE = """
            use std::collections::HashMap;
            use crate::models::{User, role::Role};
            use super::util;
            use self::inner::Thing as T;
            use rand::*;
            mod config;
            mod routes { pub fn x() {} }

            struct S { a: i32 }
            enum E { A }
            trait Tr { fn f(&self); }
            impl S {
                fn run(&self, x: i32) -> i32 {
                    if x > 0 && x < 5 { return 1; }
                    if let Some(v) = opt { }
                    for i in 0..3 {}
                    match x { 1 => 1, 2 | 3 => 2, _ => 3 }
                    let c = |a| a + 1;
                    0
                }
            }
            fn free() {}
            """;

    @Test
    void expandsUseTreesIntoOnePathPerItem() {
        assertEquals(List.of("a::b", "a::c::d", "a::c::e", "a::g"),
                RustLanguageSupport.expandUse("use a::{b, c::{d, e as f}, g::*};"));
        assertEquals(List.of("crate::x::Y"), RustLanguageSupport.expandUse("pub(crate) use crate::x::Y;"));
        assertEquals(List.of("a", "a::b"), RustLanguageSupport.expandUse("use a::{self, b};"));
        assertEquals(List.of("a::b::C"), RustLanguageSupport.expandUse("use a::b::C as D;"));
    }

    @Test
    void readsUsesAndModuleFiles() {
        ParsedFile f = rust.parse("src/lib.rs", SAMPLE);
        assertTrue(f.parsed());
        assertEquals(List.of("std::collections::HashMap", "crate::models::User", "crate::models::role::Role",
                "super::util", "self::inner::Thing", "rand", "self::config"),
                f.imports().stream().map(RawImport::name).toList()); // "mod routes { }" is inline: no file
    }

    @Test
    void readsTypesAndFunctions() {
        List<String> kinds = rust.parse("src/lib.rs", SAMPLE).symbols().stream()
                .map(s -> s.kind() + " " + s.name()).toList();
        assertEquals(List.of("METHOD x", "CLASS S", "ENUM E", "INTERFACE Tr", "METHOD run", "METHOD free"), kinds);
    }

    @Test
    void complexityCountsFunctionsAndDecisionPoints() {
        // functions: x, run, closure, free = 4
        // decisions: if, &&, if let, for, two match arms (the "_" arm is free) = 6
        assertEquals(10, rust.parse("src/lib.rs", SAMPLE).complexity());
    }

    @Test
    void commentsAndStringsDoNotCount() {
        String src = "// if a && b {}\nfn f() -> &'static str { \"if && for\" }\n";
        assertEquals(1, rust.parse("src/lib.rs", src).complexity());
    }

    @Test
    void positionsSurviveNonAsciiText() {
        String src = "// héllo ✓\nuse crate::a;\nfn é() {}\n";
        ParsedFile f = rust.parse("src/lib.rs", src);
        assertEquals(List.of("crate::a"), f.imports().stream().map(RawImport::name).toList());
        assertEquals(3, f.symbols().get(0).line());
    }
}
