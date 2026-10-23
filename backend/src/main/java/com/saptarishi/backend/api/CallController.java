package com.saptarishi.backend.api;

import com.saptarishi.backend.calls.CallRepository;
import com.saptarishi.backend.calls.CallRepository.Direction;
import com.saptarishi.backend.calls.CallRepository.FileMethod;
import com.saptarishi.backend.calls.CallRepository.Subgraph;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/repos")
public class CallController {

    public record MethodsResponse(boolean available, List<FileMethod> methods) {}

    private final CallRepository calls;

    public CallController(CallRepository calls) {
        this.calls = calls;
    }

    @GetMapping("/{sha}/methods")
    public MethodsResponse methods(@PathVariable String sha, @RequestParam String path) {
        return calls.available(sha)
                ? new MethodsResponse(true, calls.methodsOf(sha, path))
                : new MethodsResponse(false, List.of());
    }

    @GetMapping("/{sha}/calls")
    public ResponseEntity<Subgraph> calls(@PathVariable String sha, @RequestParam String method,
                                          @RequestParam(defaultValue = "2") int depth,
                                          @RequestParam(defaultValue = "both") String dir) {
        Direction direction = Direction.valueOf(dir.toUpperCase()); // bad value -> 400 via the existing handler
        if (calls.find(sha, method).isEmpty()) return ResponseEntity.notFound().build();
        return ResponseEntity.ok(calls.neighbourhood(sha, method, Math.max(1, Math.min(depth, 3)), direction));
    }
}