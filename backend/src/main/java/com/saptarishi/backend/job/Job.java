package com.saptarishi.backend.job;

import java.time.Instant;
import java.util.UUID;

final class Job {

    private final String id = UUID.randomUUID().toString();
    private final String sha;
    private JobStatus.State state;
    private String stage;
    private int progress;
    private String error;
    private Instant finishedAt;

    Job(String sha, JobStatus.State state, String stage, int progress) {
        this.sha = sha;
        this.state = state;
        this.stage = stage;
        this.progress = progress;
    }

    static Job completed(String sha) {
        Job job = new Job(sha, JobStatus.State.DONE, "Done", 100);
        job.finishedAt = Instant.now();
        return job;
    }

    String id() {
        return id;
    }

    String sha() {
        return sha;
    }

    synchronized void start() {
        state = JobStatus.State.RUNNING;
        stage = "Starting";
    }

    synchronized void update(String stage, int percent) {
        this.stage = stage;
        this.progress = Math.max(progress, Math.min(percent, 99));
    }

    synchronized void complete() {
        state = JobStatus.State.DONE;
        stage = "Done";
        progress = 100;
        finishedAt = Instant.now();
    }

    synchronized void fail(String message) {
        state = JobStatus.State.FAILED;
        stage = "Failed";
        error = message;
        finishedAt = Instant.now();
    }

    synchronized boolean isFinished() {
        return finishedAt != null;
    }

    synchronized boolean finishedBefore(Instant cutoff) {
        return finishedAt != null && finishedAt.isBefore(cutoff);
    }

    synchronized JobStatus snapshot() {
        return new JobStatus(id, sha, state, stage, progress, error);
    }
}