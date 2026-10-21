package com.saptarishi.backend.graph;

import com.saptarishi.backend.graph.GraphBuilder.Graph;
import com.saptarishi.backend.ingest.GithubUrl;
import com.saptarishi.backend.ingest.RepoIngestor;
import com.saptarishi.backend.parse.ParsedFile;
import com.saptarishi.backend.parse.RepoParser;
import java.io.IOException;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class GraphService {

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
        var ingested = ingestor.ingest(gh, sha);

        int total = Math.max(1, ingested.files().size());
        progress.update("Parsing files", 30);
        List<ParsedFile> parsed = parser.parseAll(ingested.sourceDir(), ingested.files(),
                done -> progress.update("Parsing files", 30 + (int) (55L * done / total)));

        progress.update("Resolving imports", 88);
        Graph graph = builder.build(parsed, false);

        progress.update("Saving graph", 94);
        repository.save(sha, gh.owner(), gh.repo(), graph, parsed);
    }
}