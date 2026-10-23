package com.saptarishi.backend.churn;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.revwalk.RevCommit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ChurnServiceTest {

    @TempDir
    Path dir;

    private void write(String name, String text) throws Exception {
        Files.writeString(dir.resolve(name), text);
    }

    private RevCommit commit(Git git, String message) throws Exception {
        PersonIdent who = new PersonIdent("test", "test@example.com");
        git.add().addFilepattern(".").call();
        return git.commit().setMessage(message).setAuthor(who).setCommitter(who).setSign(false).call();
    }

    @Test
    void countsCommitsPerFileAndSkipsTheRootCommit() throws Exception {
        try (Git git = Git.init().setDirectory(dir.toFile()).call()) {
            write("A.java", "1");
            write("B.java", "1");
            commit(git, "first");                      // root: not counted
            write("A.java", "2");
            commit(git, "second");                     // A
            write("A.java", "3");
            write("C.java", "1");
            commit(git, "third");                      // A, C
            write("B.java", "2");
            RevCommit head = commit(git, "fourth");    // B

            var c = ChurnService.count(git.getRepository(), head.getName(),
                    Set.of("A.java", "B.java", "C.java", "Gone.java"));

            assertEquals(3, c.examined());
            assertEquals(2, c.commits().get("A.java"));
            assertEquals(1, c.commits().get("B.java"));
            assertEquals(1, c.commits().get("C.java"));
            assertFalse(c.commits().containsKey("Gone.java"));
        }
    }
}