package com.saptarishi.backend.graph;

import com.saptarishi.backend.graph.GraphBuilder.Graph;
import com.saptarishi.backend.ingest.RepoIngestor;
import com.saptarishi.backend.parse.ParsedFile;
import com.saptarishi.backend.parse.RepoParser;
import java.io.IOException;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Service;

@Service
public class GraphService {

    public record Snapshot(String sha, boolean cached, Graph graph) {}

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

    /** Returns the stored graph for the repo's current commit, building and saving it on a miss. */
    public Snapshot graphFor(String url) throws IOException {
        var ingested = ingestor.ingest(url);
        String sha = ingested.sha();

        Optional<Graph> hit = repository.load(sha);
        if (hit.isPresent()) return new Snapshot(sha, true, hit.get());

        List<ParsedFile> parsed = parser.parseAll(ingested.sourceDir(), ingested.files());
        Graph graph = builder.build(parsed, false);
        repository.save(sha, ingested.owner(), ingested.repo(), graph, parsed);
        return new Snapshot(sha, false, graph);
    }
}