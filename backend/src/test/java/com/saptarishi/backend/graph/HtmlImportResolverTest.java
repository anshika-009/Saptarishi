package com.saptarishi.backend.graph;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class HtmlImportResolverTest {

    private static HtmlImportResolver of(String... paths) {
        return new HtmlImportResolver(List.of(paths));
    }

    @Test void anAddressWithoutASlashIsRelativeToThePage() {
        HtmlImportResolver r = of("site/index.html", "site/app.js", "app.js");
        assertEquals(Optional.of("site/app.js"), r.resolve("site/index.html", "app.js"));
    }

    @Test void dotAndDotDotAreFollowed() {
        HtmlImportResolver r = of("a/b/page.html", "a/x.js", "a/b/y.js");
        assertEquals(Optional.of("a/x.js"), r.resolve("a/b/page.html", "../x.js"));
        assertEquals(Optional.of("a/b/y.js"), r.resolve("a/b/page.html", "./y.js"));
    }

    @Test void aViteStylePageFindsItsEntryScriptAtTheRoot() {
        HtmlImportResolver r = of("index.html", "src/main.jsx");
        assertEquals(Optional.of("src/main.jsx"), r.resolve("index.html", "/src/main.jsx"));
    }

    @Test void aRootAddressPrefersTheNearestFolderAboveThePage() {
        HtmlImportResolver r = of("docs/index.html", "docs/assets/site.js", "assets/site.js");
        assertEquals(Optional.of("docs/assets/site.js"), r.resolve("docs/index.html", "/assets/site.js"));
    }

    @Test void commonWebFoldersAreTriedForARootAddress() {
        HtmlImportResolver r = of("index.html", "public/app.js");
        assertEquals(Optional.of("public/app.js"), r.resolve("index.html", "/app.js"));
    }

    @Test void queryAndFragmentAreDropped() {
        HtmlImportResolver r = of("index.html", "app.js");
        assertEquals(Optional.of("app.js"), r.resolve("index.html", "app.js?v=3#x"));
    }

    @Test void percentEscapesAreDecoded() {
        HtmlImportResolver r = of("index.html", "my file.js");
        assertEquals(Optional.of("my file.js"), r.resolve("index.html", "my%20file.js"));
    }

    @Test void aFolderAddressMeansItsIndexPage() {
        HtmlImportResolver r = of("index.html", "about/index.html");
        assertEquals(Optional.of("about/index.html"), r.resolve("index.html", "about/"));
        assertEquals(Optional.of("about/index.html"), r.resolve("index.html", "about"));
    }

    @Test void anAddressWithoutAnExtensionMayBeAPageServedWithoutIt() {
        HtmlImportResolver r = of("index.html", "contact.html");
        assertEquals(Optional.of("contact.html"), r.resolve("index.html", "contact"));
    }

    @Test void unknownFilesAndAddressesThatLeaveTheRepositoryResolveToNothing() {
        HtmlImportResolver r = of("a.html", "b.js");
        assertEquals(Optional.empty(), r.resolve("a.html", "missing.js"));
        assertEquals(Optional.empty(), r.resolve("a.html", "../../b.js"));
    }

    @Test void aPageNeverLinksToItself() {
        HtmlImportResolver r = of("index.html");
        assertEquals(Optional.empty(), r.resolve("index.html", "index.html"));
    }
}