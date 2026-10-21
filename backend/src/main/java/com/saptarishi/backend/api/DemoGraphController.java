package com.saptarishi.backend.api;

import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/demo")
public class DemoGraphController {

    public record FileNode(String id, String folder, String language, int complexity, int tokens) {}
    public record Edge(String source, String target) {}
    public record GraphResponse(List<FileNode> nodes, List<Edge> edges) {}

    @GetMapping("/graph")
    public GraphResponse graph() {
        var nodes = List.of(
            new FileNode("src/app/Main.java", "src/app", "java", 3, 120),
            new FileNode("src/app/web/UserController.java", "src/app/web", "java", 8, 340),
            new FileNode("src/app/service/UserService.java", "src/app/service", "java", 14, 610),
            new FileNode("src/app/repo/UserRepository.java", "src/app/repo", "java", 4, 150),
            new FileNode("src/app/util/StringUtils.java", "src/app/util", "java", 6, 200)
        );
        var edges = List.of(
            new Edge("src/app/Main.java", "src/app/web/UserController.java"),
            new Edge("src/app/web/UserController.java", "src/app/service/UserService.java"),
            new Edge("src/app/service/UserService.java", "src/app/repo/UserRepository.java"),
            new Edge("src/app/service/UserService.java", "src/app/util/StringUtils.java")
        );
        return new GraphResponse(nodes, edges);
    }
}