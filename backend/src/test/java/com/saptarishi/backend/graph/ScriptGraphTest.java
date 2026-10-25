package com.saptarishi.backend.graph;

import static org.junit.jupiter.api.Assertions.*;
import com.saptarishi.backend.graph.GraphBuilder.Graph;
import com.saptarishi.backend.graph.GraphBuilder.GraphEdge;
import com.saptarishi.backend.parse.JavaScriptLanguageSupport;
import com.saptarishi.backend.parse.ParsedFile;
import com.saptarishi.backend.parse.PythonLanguageSupport;
import com.saptarishi.backend.parse.TypeScriptLanguageSupport;
import java.util.List;
import org.junit.jupiter.api.Test;

class ScriptGraphTest {

    @Test void javaScriptAndTypeScriptFilesAreLinked() {
        ParsedFile a = new TypeScriptLanguageSupport().parse("src/a.ts", "import { b } from './b';\nimport r from 'react';\n");
        ParsedFile b = new JavaScriptLanguageSupport().parse("src/b.js", "module.exports = { b: 1 };\n");
        Graph g = new GraphBuilder().build(List.of(a, b), false);
        assertEquals(List.of(new GraphEdge("src/a.ts", "src/b.js", "IMPORT")), g.edges());
    }

    @Test void pythonFilesAreLinkedOncePerTarget() {
        PythonLanguageSupport py = new PythonLanguageSupport();
        ParsedFile main = py.parse("main.py", "from app import models\nfrom app.models import User\nimport app.models\n");
        ParsedFile init = py.parse("app/__init__.py", "");
        ParsedFile models = py.parse("app/models.py", "class User: pass\n");
        Graph g = new GraphBuilder().build(List.of(main, init, models), false);
        assertEquals(2, g.edges().size());
        assertTrue(g.edges().contains(new GraphEdge("main.py", "app/models.py", "IMPORT")));
        assertTrue(g.edges().contains(new GraphEdge("main.py", "app/__init__.py", "IMPORT")));
    }

    @Test void testFileConventionsAreRecognised() {
        assertTrue(HealthScore.isTestPath("src/a.test.ts"));
        assertTrue(HealthScore.isTestPath("src/a.spec.jsx"));
        assertTrue(HealthScore.isTestPath("web/__tests__/a.js"));
        assertTrue(HealthScore.isTestPath("pkg/test_models.py"));
        assertTrue(HealthScore.isTestPath("pkg/models_test.py"));
        assertTrue(HealthScore.isTestPath("conftest.py"));
        assertFalse(HealthScore.isTestPath("src/latest.ts"));
        assertFalse(HealthScore.isTestPath("pkg/contest.py"));
        assertTrue(HealthScore.isBoilerplate("types/index.d.ts"));
    }
}
