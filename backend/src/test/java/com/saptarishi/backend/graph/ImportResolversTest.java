package com.saptarishi.backend.graph;

import static org.junit.jupiter.api.Assertions.*;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class ImportResolversTest {

    private final ScriptImportResolver js = new ScriptImportResolver(List.of(
            "src/app.ts", "src/util.ts", "src/lib/index.ts", "src/lib/a.js", "src/ui/Button.tsx",
            "src/data.json", "index.js", "config.js"));

    @Test void relativeWithoutExtension() {
        assertEquals(Optional.of("src/util.ts"), js.resolve("src/app.ts", "./util"));
        assertEquals(Optional.of("src/util.ts"), js.resolve("src/ui/Button.tsx", "../util"));
    }

    @Test void directoryResolvesToIndex() {
        assertEquals(Optional.of("src/lib/index.ts"), js.resolve("src/app.ts", "./lib"));
    }

    @Test void explicitExtensionAndTypeScriptJsConvention() {
        assertEquals(Optional.of("src/lib/a.js"), js.resolve("src/lib/index.ts", "./a.js"));
        assertEquals(Optional.of("src/util.ts"), js.resolve("src/app.ts", "./util.js"));
        assertEquals(Optional.of("src/data.json"), js.resolve("src/app.ts", "./data.json"));
    }

    @Test void rootAliasesAndBaseUrl() {
        assertEquals(Optional.of("src/ui/Button.tsx"), js.resolve("src/app.ts", "@/ui/Button"));
        assertEquals(Optional.of("src/util.ts"), js.resolve("src/ui/Button.tsx", "util"));
        assertEquals(Optional.of("config.js"), js.resolve("src/app.ts", "config"));
    }

    @Test void packagesAndMissingFilesResolveToNothing() {
        assertEquals(Optional.empty(), js.resolve("src/app.ts", "react"));
        assertEquals(Optional.empty(), js.resolve("src/app.ts", "@babel/core"));
        assertEquals(Optional.empty(), js.resolve("src/app.ts", "./nope"));
        assertEquals(Optional.empty(), js.resolve("index.js", "../outside"));
    }

    @Test void queryStringIsIgnoredAndSelfImportIsNot() {
        assertEquals(Optional.of("src/util.ts"), js.resolve("src/app.ts", "./util?raw"));
        assertEquals(Optional.empty(), js.resolve("src/util.ts", "./util"));
    }

    private final PythonImportResolver py = new PythonImportResolver(List.of(
            "app/__init__.py", "app/models.py", "app/api/__init__.py", "app/api/routes.py",
            "src/core/engine.py", "scripts/run.py", "scripts/helper.py", "main.py"));

    @Test void absoluteFromRepoRootAndPackages() {
        assertEquals(Optional.of("app/models.py"), py.resolve("main.py", "app.models"));
        assertEquals(Optional.of("app/api/__init__.py"), py.resolve("main.py", "app.api"));
        assertEquals(Optional.of("app/__init__.py"), py.resolve("main.py", "app"));
    }

    @Test void absoluteFromSrcLayoutAndNeighbours() {
        assertEquals(Optional.of("src/core/engine.py"), py.resolve("main.py", "core.engine"));
        assertEquals(Optional.of("scripts/helper.py"), py.resolve("scripts/run.py", "helper"));
        assertEquals(Optional.empty(), py.resolve("main.py", "helper"));
    }

    @Test void relativeImports() {
        assertEquals(Optional.of("app/api/routes.py"), py.resolve("app/api/__init__.py", ".routes"));
        assertEquals(Optional.of("app/models.py"), py.resolve("app/api/routes.py", "..models"));
        assertEquals(Optional.of("app/api/__init__.py"), py.resolve("app/api/routes.py", "."));
        assertEquals(Optional.of("app/__init__.py"), py.resolve("app/api/routes.py", ".."));
        assertEquals(Optional.empty(), py.resolve("main.py", "..too.far"));
    }

    @Test void standardLibraryAndPackagesResolveToNothing() {
        assertEquals(Optional.empty(), py.resolve("main.py", "os.path"));
        assertEquals(Optional.empty(), py.resolve("main.py", "requests"));
    }
}
