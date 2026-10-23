package com.saptarishi.backend.api;

import com.saptarishi.backend.pathfinder.PathfinderService;
import com.saptarishi.backend.pathfinder.PathfinderService.Result;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/repos")
public class PathfinderController {

    private final PathfinderService pathfinder;

    public PathfinderController(PathfinderService pathfinder) {
        this.pathfinder = pathfinder;
    }

    @GetMapping("/{sha}/pathfinder")
    public ResponseEntity<Result> get(@PathVariable String sha) {
        return pathfinder.pathfinder(sha)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }
}