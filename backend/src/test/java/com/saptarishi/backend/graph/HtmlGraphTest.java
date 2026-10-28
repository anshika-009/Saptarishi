package com.saptarishi.backend.graph;

import static org.junit.jupiter.api.Assertions.*;

import com.saptarishi.backend.graph.GraphBuilder.Graph;
import com.saptarishi.backend.graph.GraphBuilder.GraphEdge;
import com.saptarishi.backend.ingest.SourceFilter;
import com.saptarishi.backend.parse.HtmlLanguageSupport;
import com.saptarishi.backend.parse.JavaScriptLanguageSupport;
import com.saptarishi.backend.parse.ParsedFile;
import java.util.List;
import org.junit.jupiter.api.Test;

class HtmlGraphTest {

    private final HtmlLanguageSupport html = new HtmlLanguageSupport();
    private final JavaScriptLanguageSupport js = new JavaScriptLanguageSupport();

    @Test void aPageIsLinkedToTheScriptsItLoads() {
        ParsedFile page = html.parse("index.html",
                "<div id=\"root\"></div>\n<script type=\"module\" src=\"/src/main.jsx\"></script>\n");
        ParsedFile main = js.parse("src/main.jsx", "import App from './App.jsx';\n");
        ParsedFile app = js.parse("src/App.jsx", "export default function App() { return null; }\n");
        Graph g = new GraphBuilder().build(List.of(page, main, app), false);
        assertEquals(2, g.edges().size());
        assertTrue(g.edges().contains(new GraphEdge("index.html", "src/main.jsx", "IMPORT")));
        assertTrue(g.edges().contains(new GraphEdge("src/main.jsx", "src/App.jsx", "IMPORT")));
    }

    @Test void htmlFilesAreNodesWithNoComplexity() {
        ParsedFile page = html.parse("index.html", "<p>hi</p>\n");
        Graph g = new GraphBuilder().build(List.of(page), false);
        assertEquals(1, g.nodes().size());
        assertEquals("html", g.nodes().get(0).language());
        assertEquals(0, g.nodes().get(0).complexity());
    }

    @Test void aPageLinksToAnIframePage() {
        ParsedFile a = html.parse("a.html", "<iframe src=\"b.html\"></iframe>");
        ParsedFile b = html.parse("b.html", "<p>b</p>");
        Graph g = new GraphBuilder().build(List.of(a, b), false);
        assertEquals(List.of(new GraphEdge("a.html", "b.html", "IMPORT")), g.edges());
    }

    @Test void aStylesheetThatIsNotAGraphNodeGetsNoEdge() {
        ParsedFile page = html.parse("index.html", "<link rel=\"stylesheet\" href=\"style.css\">");
        ParsedFile css = new ParsedFile("style.css", "text", "", List.of(), List.of(), 0, 0, 1, true);
        Graph g = new GraphBuilder().build(List.of(page, css), false);
        assertTrue(g.edges().isEmpty()); // css is a plain text file here, not a node
    }

    @Test void navigationLinksDoNotCreateCycles() {
        ParsedFile a = html.parse("a.html", "<a href=\"b.html\">b</a>");
        ParsedFile b = html.parse("b.html", "<a href=\"a.html\">a</a>");
        assertTrue(new GraphBuilder().build(List.of(a, b), false).edges().isEmpty());
    }

    @Test void htmlFilesAreClassifiedAsHtmlAndCssStaysText() {
        SourceFilter f = new SourceFilter();
        assertEquals("html", f.classify("index.html"));
        assertEquals("html", f.classify("old/Page.HTM"));
        assertEquals("text", f.classify("style.css"));
    }
}