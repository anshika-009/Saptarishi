package com.saptarishi.backend.churn;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.diff.DiffEntry;
import org.eclipse.jgit.diff.DiffFormatter;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.util.FileUtils;
import org.eclipse.jgit.util.io.NullOutputStream;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Code churn: how many recent commits touched each file. Git history is read lazily (only when the
 * heatmap is first requested) with a shallow, bare clone, then stored so it is read once per snapshot.
 */
@Service
public class ChurnService {

    /** examined: commits counted. commits: path -> number of those commits that touched it. */
    public record Churn(int examined, Map<String, Integer> commits) {}

    private final ChurnRepository store;
    private final int depth;
    private final int timeoutSeconds;
    private final ConcurrentHashMap<String, CompletableFuture<Churn>> running = new ConcurrentHashMap<>();

    public ChurnService(ChurnRepository store,
                        @Value("${saptarishi.churn.depth:300}") int depth,
                        @Value("${saptarishi.churn.timeout-seconds:90}") int timeoutSeconds) {
        this.store = store;
        this.depth = depth;
        this.timeoutSeconds = timeoutSeconds;
    }

    /** Stored result if there is one, otherwise clone, count and store. Concurrent calls share one clone. */
    public Churn get(String sha, String owner, String repo, Set<String> paths) {
        Optional<Integer> done = store.examined(sha);
        if (done.isPresent()) return new Churn(done.get(), store.load(sha));

        CompletableFuture<Churn> mine = new CompletableFuture<>();
        CompletableFuture<Churn> other = running.putIfAbsent(sha, mine);
        if (other != null) return other.join();
        try {
            Churn c = fromGit(owner, repo, sha, paths);
            store.save(sha, c.examined(), c.commits());
            mine.complete(c);
            return c;
        } catch (RuntimeException e) {
            mine.completeExceptionally(e);
            throw e;
        } finally {
            running.remove(sha);
        }
    }

    private Churn fromGit(String owner, String repo, String sha, Set<String> paths) {
        Path dir = null;
        try {
            dir = Files.createTempDirectory("churn-");
            try (Git git = Git.cloneRepository()
                    .setURI("https://github.com/" + owner + "/" + repo + ".git")
                    .setDirectory(dir.toFile())
                    .setBare(true)
                    .setDepth(depth)
                    .setCloneAllBranches(false)
                    .setTimeout(timeoutSeconds)
                    .call()) {
                return count(git.getRepository(), sha, paths);
            }
        } catch (IOException | GitAPIException e) {
            throw new IllegalStateException("Could not read git history", e);
        } finally {
            if (dir != null) deleteQuietly(dir);
        }
    }

    /**
     * Walks back from sha (or HEAD if sha is not in the clone) and counts, per file in paths, the
     * commits that changed it. Root commits (and the shallow boundary) and merges are skipped:
     * diffing a root commit would count every file once.
     */
    static Churn count(Repository repo, String sha, Set<String> paths) throws IOException {
        ObjectId start = null;
        try {
            start = repo.resolve(sha + "^{commit}");
        } catch (IOException ignored) {
            // not in the clone: fall back to HEAD
        }
        if (start == null) start = repo.resolve("HEAD");
        if (start == null) throw new IOException("Repository has no commits");

        Set<ObjectId> shallow = repo.getObjectDatabase().getShallowCommits();
        Map<String, Integer> counts = new HashMap<>();
        int examined = 0;
        try (RevWalk walk = new RevWalk(repo);
             DiffFormatter diff = new DiffFormatter(NullOutputStream.INSTANCE)) {
            diff.setRepository(repo);
            diff.setDetectRenames(false);
            walk.markStart(walk.parseCommit(start));
            for (RevCommit c : walk) {
                if (c.getParentCount() != 1 || shallow.contains(c.getId())) continue;
                RevCommit parent = walk.parseCommit(c.getParent(0).getId());
                examined++;
                for (DiffEntry d : diff.scan(parent.getTree(), c.getTree())) {
                    if (d.getChangeType() == DiffEntry.ChangeType.DELETE) continue;
                    String path = d.getNewPath();
                    if (paths.contains(path)) counts.merge(path, 1, Integer::sum);
                }
            }
        }
        return new Churn(examined, counts);
    }

    private static void deleteQuietly(Path dir) {
        try {
            FileUtils.delete(dir.toFile(),
                    FileUtils.RECURSIVE | FileUtils.RETRY | FileUtils.SKIP_MISSING);
        } catch (IOException ignored) {
            // temp folder; the OS will clean it up eventually
        }
    }
}