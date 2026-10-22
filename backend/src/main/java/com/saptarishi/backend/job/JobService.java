package com.saptarishi.backend.job;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Optional;

public interface JobService {

    JobStatus submit(String url) throws IOException;

    /** Takes ownership of the temp file: it is always deleted, whether or not an analysis runs. */
    JobStatus submitUpload(String name, String sha, Path zip);

    Optional<JobStatus> find(String jobId);
}