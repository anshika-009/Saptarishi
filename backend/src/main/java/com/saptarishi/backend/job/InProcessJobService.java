package com.saptarishi.backend.job;

import com.saptarishi.backend.graph.GraphRepository;
import com.saptarishi.backend.graph.GraphService;
import com.saptarishi.backend.ingest.GithubUrl;
import com.saptarishi.backend.ingest.RepoIngestor;
import com.saptarishi.backend.ingest.SafeUnzipper.UnsafeArchiveException;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class InProcessJobService implements JobService {

    private static final Logger log = LoggerFactory.getLogger(InProcessJobService.class);
    private static final int QUEUE_CAPACITY = 20;
    private static final Duration RETENTION = Duration.ofHours(1);

    private final RepoIngestor ingestor;
    private final GraphRepository repository;
    private final GraphService pipeline;
    private final ThreadPoolExecutor executor;
    private final Map<String, Job> jobsById = new ConcurrentHashMap<>();
    private final Map<String, Job> activeBySha = new HashMap<>();
    private final Object lock = new Object();

    public InProcessJobService(RepoIngestor ingestor, GraphRepository repository, GraphService pipeline,
                               @Value("${saptarishi.jobs.workers:2}") int workers) {
        this.ingestor = ingestor;
        this.repository = repository;
        this.pipeline = pipeline;
        AtomicInteger counter = new AtomicInteger();
        this.executor = new ThreadPoolExecutor(workers, workers, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(QUEUE_CAPACITY),
                r -> {
                    Thread t = new Thread(r, "analysis-" + counter.incrementAndGet());
                    t.setDaemon(true);
                    return t;
                });
    }

    @Override
    public JobStatus submit(String url) throws IOException {
        GithubUrl gh = GithubUrl.parse(url);
        String sha = ingestor.resolveSha(gh);

        if (repository.exists(sha)) {
            Job cached = Job.completed(sha);
            jobsById.put(cached.id(), cached);
            return cached.snapshot();
        }

        synchronized (lock) {
            purgeExpired();
            Job running = activeBySha.get(sha);
            if (running != null) return running.snapshot();

            Job job = new Job(sha, JobStatus.State.QUEUED, "Queued", 0);
            activeBySha.put(sha, job);
            jobsById.put(job.id(), job);
            try {
                executor.execute(() -> run(job, gh));
            } catch (RejectedExecutionException e) {
                activeBySha.remove(sha);
                jobsById.remove(job.id());
                throw new RejectedExecutionException("Server is busy, try again shortly");
            }
            return job.snapshot();
        }
    }

    @Override
    public Optional<JobStatus> find(String jobId) {
        return Optional.ofNullable(jobsById.get(jobId)).map(Job::snapshot);
    }

    private void run(Job job, GithubUrl gh) {
        job.start();
        try {
            pipeline.analyze(gh, job.sha(), job::update);
            job.complete();
        } catch (IllegalArgumentException | UnsafeArchiveException | IOException e) {
            job.fail(e.getMessage());
        } catch (Exception e) {
            log.error("Analysis failed for {}", job.sha(), e);
            job.fail("Internal error while analysing the repository");
        } finally {
            if (!job.isFinished()) job.fail("Analysis terminated unexpectedly");
            synchronized (lock) {
                activeBySha.remove(job.sha(), job);
            }
        }
    }

    private void purgeExpired() {
        Instant cutoff = Instant.now().minus(RETENTION);
        jobsById.values().removeIf(j -> j.finishedBefore(cutoff));
    }

    @PreDestroy
    void shutdown() {
        executor.shutdownNow();
    }
}