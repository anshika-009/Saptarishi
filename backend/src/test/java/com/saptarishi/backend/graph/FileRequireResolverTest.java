package com.saptarishi.backend.graph;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class FileRequireResolverTest {

    private final FileRequireResolver rb = new FileRequireResolver(List.of(
            "app/main.rb", "app/helpers/util.rb", "lib/my_app/user.rb", "lib/my_app.rb",
            "other/config.rb", "README.md"), "rb");

    private final FileRequireResolver php = new FileRequireResolver(List.of(
            "src/index.php", "src/inc/db.php", "lib/x.php"), "php");

    @Test
    void relativeNamesAreRelativeToTheIncludingFileAndMayOmitTheExtension() {
        assertEquals(Optional.of("app/helpers/util.rb"), rb.resolve("app/main.rb", "./helpers/util"));
        assertEquals(Optional.of("app/main.rb"), rb.resolve("app/helpers/util.rb", "../main.rb"));
    }

    @Test
    void plainNamesAreSearchedInLibFoldersWalkingUp() {
        assertEquals(Optional.of("lib/my_app/user.rb"), rb.resolve("app/main.rb", "my_app/user"));
        assertEquals(Optional.of("lib/my_app.rb"), rb.resolve("app/helpers/util.rb", "my_app"));
    }

    @Test
    void gemsAndStandardLibrariesResolveToNothing() {
        assertEquals(Optional.empty(), rb.resolve("app/main.rb", "json"));
        // config.rb exists once, but nowhere near app/
        assertEquals(Optional.empty(), rb.resolve("app/main.rb", "config"));
    }

    @Test
    void aFileNeverResolvesToItselfAndOtherExtensionsAreIgnored() {
        assertEquals(Optional.empty(), rb.resolve("app/main.rb", "./main"));
        assertEquals(Optional.empty(), rb.resolve("app/main.rb", "../README"));
    }

    @Test
    void phpIncludesAreRelativeToTheIncludingFile() {
        assertEquals(Optional.of("src/inc/db.php"), php.resolve("src/index.php", "./inc/db.php"));
        assertEquals(Optional.of("lib/x.php"), php.resolve("src/index.php", "../lib/x.php"));
        assertEquals(Optional.empty(), php.resolve("src/index.php", "./missing.php"));
    }
}
