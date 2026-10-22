package com.saptarishi.backend.graph;

import com.saptarishi.backend.graph.GraphBuilder.Graph;
import com.saptarishi.backend.ingest.GithubUrl;
import com.saptarishi.backend.ingest.RepoIngestor;
import com.saptarishi.backend.ingest.RepoIngestor.IngestResult;
import com.saptarishi.backend.parse.ParsedFile;
import com.saptarishi.backend.parse.RepoParser;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class GraphService {

    @FunctionalInterface
    private interface Ingest {
        IngestResult run() throws IOException;
    }

    private final RepoIngestor ingestor;
    private final RepoParser parser;
    private final GraphBuilder builder;
    private final GraphRepository repository;

    public GraphService(RepoIngestor ingestor, RepoParser parser,
                        GraphBuilder builder, GraphRepository repository) {
        this.ingestor = ingestor;
        this.parser = parser;
        this.builder = builder;
        this.repository = repository;
    }

    public void analyze(GithubUrl gh, String sha, ProgressListener progress) throws IOException {
        progress.update("Downloading repository", 5);
        run(gh.owner(), gh.repo(), sha, () -> ingestor.ingest(gh, sha), progress);
    }

    public void analyzeUpload(String name, String sha, Path zip, ProgressListener progress) throws IOException {
        progress.update("Extracting archive", 5);
        run("upload", name, sha, () -> ingestor.ingestZip(name, sha, zip), progress);
    }

    private void run(String owner, String repo, String sha, Ingest ingest, ProgressListener progress)
            throws IOException {
        var ingested = ingest.run();

        int total = Math.max(1, ingested.files().size());
        progress.update("Parsing files", 30);
        List<ParsedFile> parsed = parser.parseAll(ingested.sourceDir(), ingested.files(),
                done -> progress.update("Parsing files", 30 + (int) (55L * done / total)));

        progress.update("Resolving imports", 88);
        Graph graph = builder.build(parsed, false);
        if (graph.nodes().isEmpty()) throw new IllegalArgumentException("No supported source files found");

        progress.update("Saving graph", 94);
        repository.save(sha, owner, repo, graph, parsed);
    }
}