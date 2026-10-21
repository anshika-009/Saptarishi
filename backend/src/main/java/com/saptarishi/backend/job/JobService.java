package com.saptarishi.backend.job;

import java.io.IOException;
import java.util.Optional;

public interface JobService {

    JobStatus submit(String url) throws IOException;

    Optional<JobStatus> find(String jobId);
}