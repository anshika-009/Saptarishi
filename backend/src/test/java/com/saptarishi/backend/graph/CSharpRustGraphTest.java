package com.saptarishi.backend.graph;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.saptarishi.backend.graph.GraphBuilder.Graph;
import com.saptarishi.backend.graph.GraphBuilder.GraphEdge;
import com.saptarishi.backend.parse.ParsedFile;
import com.saptarishi.backend.parse.ParsedFile.RawImport;
import com.saptarishi.backend.parse.ParsedFile.Symbol;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

class CSharpRustGraphTest {

    // ---- C#

    private static ParsedFile cs(String path, String ns, List<RawImport> imports, List<String> types, List<String> mentions) {
        return new ParsedFile(path, "csharp", ns, imports,
                types.stream().map(t -> new Symbol(t, "CLASS", 1)).toList(), 10, 3, 20, true, mentions);
    }

    private static RawImport using(String ns) { return new RawImport(ns, false, false); }

    private final ParsedFile a = cs("A.cs", "App.Services", List.of(using("App.Models")), List.of("UserService"), List.of("User", "Role2"));
    private final ParsedFile b = cs("B.cs", "App.Models", List.of(), List.of("User"), List.of("User"));
    private final ParsedFile c = cs("C.cs", "App.Models", List.of(), List.of("Role"), List.of("Role"));
    private final ParsedFile d = cs("D.cs", "App.Services", List.of(), List.of("Helper"), List.of("Helper"));
    private final ParsedFile e = cs("E.cs", "Other", List.of(), List.of("Thing"), List.of("Role"));
    private final ParsedFile g = cs("G.cs", "Global", List.of(new RawImport("App.Models", false, true)), List.of("G"), List.of());

    @Test
    void usingLinksOnlyToFilesWhoseTypesAreMentioned() {
        CSharpImportResolver r = new CSharpImportResolver(List.of(a, b, c, d, e, g));
        assertEquals(Set.of("B.cs"), r.resolve(a)); // User is mentioned, Role is not
    }

    @Test
    void sameNamespaceNeedsNoUsing() {
        ParsedFile needsHelper = cs("N.cs", "App.Services", List.of(), List.of("N"), List.of("Helper"));
        CSharpImportResolver r = new CSharpImportResolver(List.of(a, d, needsHelper));
        assertEquals(Set.of("D.cs"), r.resolveReferences(needsHelper));
        assertEquals(Set.of(), r.resolveReferences(a));
    }

    @Test
    void enclosingNamespacesAreVisible() {
        ParsedFile inner = cs("I.cs", "App.Models.Deep", List.of(), List.of("I"), List.of("User"));
        assertEquals(Set.of("B.cs"), new CSharpImportResolver(List.of(b, inner)).resolveReferences(inner));
    }

    @Test
    void aliasAndUsingStaticNameATypeSoTheNamespaceAroundItIsUsed() {
        ParsedFile alias = cs("X.cs", "Elsewhere", List.of(using("App.Models.User")), List.of("X"), List.of("User"));
        assertEquals(Set.of("B.cs"), new CSharpImportResolver(List.of(alias, b, c)).resolve(alias));
    }

    @Test
    void globalUsingAppliesToEveryFile() {
        assertEquals(Set.of("C.cs"), new CSharpImportResolver(List.of(a, b, c, e, g)).resolve(e));
    }

    @Test
    void graphHasImportAndReferenceEdges() {
        ParsedFile needsHelper = cs("N.cs", "App.Services", List.of(using("App.Models")), List.of("N"), List.of("Helper", "User"));
        Graph graph = new GraphBuilder().build(List.of(needsHelper, b, d), false);
        assertTrue(graph.edges().contains(new GraphEdge("N.cs", "B.cs", "IMPORT")));
        assertTrue(graph.edges().contains(new GraphEdge("N.cs", "D.cs", "REFERENCE")));
        assertEquals(2, graph.edges().size());
    }

    // ---- Rust

    private static ParsedFile rs(String path, String... uses) {
        return new ParsedFile(path, "rust", "", java.util.Arrays.stream(uses).map(u -> new RawImport(u, false, false)).toList(),
                List.of(), 10, 3, 20, true);
    }

    private final List<String> layout = List.of(
            "src/lib.rs", "src/util.rs", "src/models/mod.rs", "src/models/role.rs",
            "src/api/mod.rs", "src/api/handler.rs",
            "crates/other-crate/src/lib.rs", "crates/other-crate/src/thing.rs");

    @Test
    void resolvesCratePathsToTheDeepestModuleFile() {
        RustImportResolver r = new RustImportResolver(layout);
        assertEquals(Optional.of("src/models/role.rs"), r.resolve("src/api/handler.rs", "crate::models::role::Role"));
        assertEquals(Optional.of("src/models/mod.rs"), r.resolve("src/api/handler.rs", "crate::models::User"));
        assertEquals(Optional.of("src/lib.rs"), r.resolve("src/api/handler.rs", "crate::Config"));
        assertEquals(Optional.of("src/util.rs"), r.resolve("src/lib.rs", "self::util"));
        assertEquals(Optional.of("src/models/role.rs"), r.resolve("src/lib.rs", "models::role::Role"));
    }

    @Test
    void resolvesSuper() {
        RustImportResolver r = new RustImportResolver(layout);
        assertEquals(Optional.of("src/api/mod.rs"), r.resolve("src/api/handler.rs", "super::Config"));
        assertEquals(Optional.of("src/util.rs"), r.resolve("src/api/mod.rs", "super::util"));
        assertEquals(Optional.of("src/util.rs"), r.resolve("src/api/handler.rs", "super::super::util"));
        assertEquals(Optional.empty(), r.resolve("src/lib.rs", "super::x")); // a crate root has no parent
    }

    @Test
    void otherCratesInTheRepositoryAreFoundByFolderName() {
        RustImportResolver r = new RustImportResolver(layout);
        assertEquals(Optional.of("crates/other-crate/src/thing.rs"), r.resolve("src/lib.rs", "other_crate::thing::X"));
        assertEquals(Optional.of("crates/other-crate/src/lib.rs"), r.resolve("crates/other-crate/src/thing.rs", "crate::X"));
    }

    @Test
    void standardLibraryRegistryCratesAndSelfResolveToNothing() {
        RustImportResolver r = new RustImportResolver(layout);
        assertEquals(Optional.empty(), r.resolve("src/lib.rs", "std::collections::HashMap"));
        assertEquals(Optional.empty(), r.resolve("src/lib.rs", "serde::Serialize"));
        assertEquals(Optional.empty(), r.resolve("src/lib.rs", "crate::Missing")); // resolves to itself
    }

    @Test
    void aNonRootFileHasItsOwnModuleFolder() {
        RustImportResolver r = new RustImportResolver(List.of("src/lib.rs", "src/a.rs", "src/a/b.rs"));
        assertEquals(Optional.of("src/a/b.rs"), r.resolve("src/a.rs", "self::b"));
        assertEquals(Optional.of("src/a.rs"), r.resolve("src/a/b.rs", "super::X"));
    }

    @Test
    void graphHasRustEdgesOncePerTarget() {
        List<ParsedFile> files = List.of(
                rs("src/lib.rs", "self::util", "self::models"),
                rs("src/util.rs"), rs("src/models/mod.rs", "self::role"), rs("src/models/role.rs", "crate::util::A", "crate::util::B"));
        Graph g = new GraphBuilder().build(files, false);
        assertEquals(4, g.edges().size());
        assertTrue(g.edges().contains(new GraphEdge("src/models/role.rs", "src/util.rs", "IMPORT")));
    }

    @Test
    void testAndGeneratedFileConventions() {
        assertTrue(HealthScore.isTestPath("src/UserTests.cs"));
        assertTrue(HealthScore.isTestPath("MyApp.Tests/Foo.cs"));
        assertTrue(HealthScore.isTestPath("src/tests.rs"));
        assertTrue(HealthScore.isTestPath("tests/integration.rs"));
        assertTrue(!HealthScore.isTestPath("src/Latest.cs"));
        assertTrue(!HealthScore.isTestPath("src/Contest.cs"));
        assertTrue(HealthScore.isBoilerplate("Views/Form.Designer.cs"));
        assertTrue(HealthScore.isBoilerplate("Properties/AssemblyInfo.cs"));
    }
}
