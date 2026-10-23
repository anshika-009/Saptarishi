package com.saptarishi.backend.api;

import com.saptarishi.backend.churn.ChurnRepository;
import com.saptarishi.backend.churn.ChurnService;
import com.saptarishi.backend.graph.GraphBuilder.Graph;
import com.saptarishi.backend.graph.GraphBuilder.GraphNode;
import com.saptarishi.backend.graph.GraphRepository;
import com.saptarishi.backend.graph.Hotspots;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/repos")
public class ChurnController {

    private static final Logger log = LoggerFactory.getLogger(ChurnController.class);

    public record ChurnResponse(boolean available, String reason, int commitsExamined,
                                List<Hotspots.FileHeat> files) {}

    private final GraphRepository graphs;
    private final ChurnRepository origins;
    private final ChurnService churn;

    public ChurnController(GraphRepository graphs, ChurnRepository origins, ChurnService churn) {
        this.graphs = graphs;
        this.origins = origins;
        this.churn = churn;
    }

    @GetMapping("/{sha}/churn")
    public ResponseEntity<ChurnResponse> get(@PathVariable String sha) {
        Optional<Graph> graph = graphs.load(sha);
        Optional<ChurnRepository.Origin> origin = origins.origin(sha);
        if (graph.isEmpty() || origin.isEmpty()) return ResponseEntity.notFound().build();

        if ("upload".equals(origin.get().owner())) {
            return unavailable("Uploaded zip files carry no git history, so churn is only available for GitHub URLs.");
        }
        try {
            Set<String> paths = graph.get().nodes().stream().map(GraphNode::id).collect(Collectors.toSet());
            ChurnService.Churn c = churn.get(sha, origin.get().owner(), origin.get().repo(), paths);
            return ResponseEntity.ok(new ChurnResponse(true, null, c.examined(),
                    Hotspots.compute(graph.get().nodes(), c.commits())));
        } catch (RuntimeException e) {
            log.warn("Churn failed for {}", sha, e);
            return unavailable("Could not read git history from GitHub (the repository may be private, very large, or unreachable).");
        }
    }

    private static ResponseEntity<ChurnResponse> unavailable(String reason) {
        return ResponseEntity.ok(new ChurnResponse(false, reason, 0, List.of()));
    }
}