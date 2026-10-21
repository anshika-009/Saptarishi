package com.saptarishi.backend.api;

import com.saptarishi.backend.job.JobService;
import com.saptarishi.backend.job.JobStatus;
import java.io.IOException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/repos")
public class RepoController {

    public record SubmitRequest(String url) {}

    private final JobService jobs;

    public RepoController(JobService jobs) {
        this.jobs = jobs;
    }

    @PostMapping
    public ResponseEntity<JobStatus> submit(@RequestBody SubmitRequest request) throws IOException {
        JobStatus status = jobs.submit(request.url());
        return status.state() == JobStatus.State.DONE
                ? ResponseEntity.ok(status)
                : ResponseEntity.accepted().body(status);
    }
}