package com.saptarishi.backend.graph;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.saptarishi.backend.parse.ParsedFile;
import com.saptarishi.backend.parse.ParsedFile.RawImport;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class SamePackageReferenceTest {

    private static ParsedFile file(String path, String pkg, List<RawImport> imports, List<String> refs) {
        return new ParsedFile(path, "java", pkg, imports, List.of(), 0, 0, 0, true, refs);
    }

    @Test
    void usedClassInSamePackageBecomesEdge() {
        var owner = file("src/a/Owner.java", "a", List.of(), List.of());
        var ctl = file("src/a/OwnerController.java", "a", List.of(), List.of("Owner", "String"));
        var r = new JavaImportResolver(List.of(owner, ctl));
        assertEquals(Set.of("src/a/Owner.java"), r.resolveReferences(ctl));
    }

    @Test
    void explicitImportShadowsSamePackageClass() {
        var local = file("src/a/Owner.java", "a", List.of(), List.of());
        var other = file("src/b/Owner.java", "b", List.of(), List.of());
        var user = file("src/a/Service.java", "a",
                List.of(new RawImport("b.Owner", false, false)), List.of("Owner"));
        var r = new JavaImportResolver(List.of(local, other, user));
        assertEquals(Set.of(), r.resolveReferences(user)); // Owner means b.Owner here
        assertEquals(Set.of("src/b/Owner.java"), r.resolve(user));
    }

    @Test
    void classNeverReferencesItself() {
        var f = file("src/a/Node.java", "a", List.of(), List.of("Node"));
        assertEquals(Set.of(), new JavaImportResolver(List.of(f)).resolveReferences(f));
    }
}