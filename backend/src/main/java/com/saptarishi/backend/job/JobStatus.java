package com.saptarishi.backend.job;

public record JobStatus(String jobId, String sha, State state, String stage, int progress, String error) {

    public enum State { QUEUED, RUNNING, DONE, FAILED }
}