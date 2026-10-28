package com.saptarishi.backend.parse;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;

class HtmlLanguageSupportTest {

    private final HtmlLanguageSupport html = new HtmlLanguageSupport();

    private static List<String> names(ParsedFile p) {
        return p.imports().stream().map(ParsedFile.RawImport::name).toList();
    }

    @Test void findsWhatAPageLoads() {
        String src = """
            <!DOCTYPE html>
            <html>
            <head>
              <link rel="stylesheet" href="css/app.css">
              <LINK REL=icon HREF=favicon.ico>
              <script src="/src/main.jsx" type="module"></script>
            </head>
            <body><iframe src='frame.html'></iframe></body>
            </html>
            """;
        assertEquals(List.of("css/app.css", "favicon.ico", "/src/main.jsx", "frame.html"),
                names(html.parse("index.html", src)));
    }

    @Test void selfClosingLinkIsFound() {
        assertEquals(List.of("a.css"), names(html.parse("x.html", "<link rel=\"stylesheet\" href=\"a.css\" />")));
    }

    @Test void linksToOtherPagesAreNavigationNotImports() {
        assertTrue(html.parse("x.html", "<a href=\"about.html\">about</a><img src=\"a.png\">").imports().isEmpty());
    }

    @Test void addressesThatCannotNameAFileAreIgnored() {
        String src = """
            <script src="https://cdn.example.com/a.js"></script>
            <script src="//cdn.example.com/b.js"></script>
            <link href="mailto:me@example.com">
            <link href="#top">
            <link href="data:image/png;base64,AAAA">
            <script src="{{ url_for('static', filename='a.js') }}"></script>
            <script src="<%= path %>"></script>
            <script src="${assetPath}"></script>
            <script src=""></script>
            """;
        assertTrue(html.parse("x.html", src).imports().isEmpty());
    }

    @Test void theSameAddressIsReportedOnce() {
        String src = "<script src=\"a.js\"></script><script src=\"a.js\"></script>";
        assertEquals(List.of("a.js"), names(html.parse("x.html", src)));
    }

    @Test void inlineScriptsAndStylesAreNotImports() {
        String src = "<script>import x from './x.js';</script><style>@import 'a.css';</style>";
        assertTrue(html.parse("x.html", src).imports().isEmpty());
    }

    @Test void complexityIsZeroAndLinesAreCounted() {
        ParsedFile p = html.parse("x.html", "<p>one</p>\n\n<p>two</p>\n");
        assertTrue(p.parsed());
        assertEquals(0, p.complexity());
        assertEquals(2, p.loc());
        assertTrue(p.tokens() > 0);
    }

    @Test void aBrokenPageIsStillRead() {
        ParsedFile p = html.parse("x.html", "<script src=\"a.js\"></script><div <p>");
        assertTrue(p.parsed());
        assertEquals(List.of("a.js"), names(p));
    }

    @Test void emptyFileIsParsed() {
        assertTrue(html.parse("x.html", "").parsed());
    }
}