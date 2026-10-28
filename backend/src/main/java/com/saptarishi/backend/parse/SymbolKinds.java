package com.saptarishi.backend.parse;

import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

/**
 * The kinds of symbol a {@link LanguageSupport} reports, and which of them name something a person would
 * mention in an issue. Every language maps its own constructs onto these (a Rust struct or a Go type is a
 * CLASS, a trait is an INTERFACE, a function is a METHOD), so code that reads symbols never has to know
 * which language it came from.
 */
public final class SymbolKinds {

    private SymbolKinds() { }

    /** Things declared once per name that a file is "about": classes, structs, traits, enums, records. */
    public static final Set<String> TYPES = Set.of("CLASS", "INTERFACE", "ENUM", "RECORD");

    /** Functions and methods. A CONSTRUCTOR is left out: it is named after its type and adds nothing. */
    public static final String METHOD = "METHOD";

    /** Everything an issue can name: the types and the methods. */
    public static final Set<String> NAMEABLE = Set.of("CLASS", "INTERFACE", "ENUM", "RECORD", "METHOD");

    /** {@link #NAMEABLE} as the inside of a SQL {@code IN (...)}, quoted and in a fixed order. */
    public static String nameableSql() {
        return new TreeSet<>(NAMEABLE).stream().map(k -> "'" + k + "'").collect(Collectors.joining(","));
    }
}
