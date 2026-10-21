package com.saptarishi.backend.graph;

import static org.junit.jupiter.api.Assertions.*;

import com.saptarishi.backend.parse.ParsedFile;
import com.saptarishi.backend.parse.ParsedFile.RawImport;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class JavaImportResolverTest {

    private static ParsedFile pf(String path, String pkg, RawImport... imports) {
        return new ParsedFile(path, "java", pkg, List.of(imports), List.of(), 10, 1, 5, true);
    }

    private static final ParsedFile SERVICE = pf("src/app/service/UserService.java", "app.service");
    private static final ParsedFile REPO   = pf("src/app/repo/UserRepository.java", "app.repo");
    private static final ParsedFile REPO2  = pf("src/app/repo/OrderRepository.java", "app.repo");
    private static final ParsedFile UTIL   = pf("src/app/util/StringUtils.java", "app.util");

    private JavaImportResolver resolver() {
        return new JavaImportResolver(List.of(SERVICE, REPO, REPO2, UTIL));
    }

    private Set<String> resolve(RawImport... imports) {
        ParsedFile caller = pf("src/app/Main.java", "app", imports);
        return resolver().resolve(caller);
    }

    @Test void resolvesNormalImport() {
        assertEquals(Set.of("src/app/repo/UserRepository.java"),
                resolve(new RawImport("app.repo.UserRepository", false, false)));
    }

    @Test void wildcardLinksEveryFileInPackage() {
        assertEquals(Set.of("src/app/repo/UserRepository.java", "src/app/repo/OrderRepository.java"),
                resolve(new RawImport("app.repo", false, true)));
    }

    @Test void staticImportDropsMemberName() {
        assertEquals(Set.of("src/app/util/StringUtils.java"),
                resolve(new RawImport("app.util.StringUtils.trim", true, false)));
    }

    @Test void staticWildcardResolvesToClass() {
        assertEquals(Set.of("src/app/util/StringUtils.java"),
                resolve(new RawImport("app.util.StringUtils", true, true)));
    }

    @Test void nestedClassImportResolvesToOuterFile() {
        assertEquals(Set.of("src/app/service/UserService.java"),
                resolve(new RawImport("app.service.UserService.Config", false, false)));
    }

    @Test void externalLibrariesAreIgnored() {
        assertTrue(resolve(new RawImport("java.util.List", false, false),
                           new RawImport("org.springframework.stereotype.Service", false, false),
                           new RawImport("java.util", false, true)).isEmpty());
    }

    @Test void fileNeverLinksToItself() {
        ParsedFile self = pf("src/app/repo/UserRepository.java", "app.repo",
                new RawImport("app.repo.UserRepository", false, false));
        assertTrue(new JavaImportResolver(List.of(self)).resolve(self).isEmpty());
    }
}