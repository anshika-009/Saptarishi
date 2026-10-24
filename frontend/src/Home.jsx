import { useEffect, useRef, useState } from "react";
import Logo from "./Logo";

const MAX_MB = 100;

async function readJson(res) {
  const body = await res.json().catch(() => ({}));
  if (!res.ok) {
    throw new Error(
      body.error || `Server error (${res.status}). Is the backend running?`,
    );
  }
  return body;
}

export default function Home({ onReady }) {
  const [url, setUrl] = useState(
    "https://github.com/spring-projects/spring-petclinic",
  );
  const [submitting, setSubmitting] = useState(false);
  const [jobId, setJobId] = useState(null);
  const [status, setStatus] = useState(null);
  const [error, setError] = useState(null);
  const [dragging, setDragging] = useState(false);
  const fileRef = useRef(null);

  useEffect(() => {
    if (!jobId) return;
    let stopped = false;
    let timer;

    const tick = async () => {
      try {
        const res = await fetch(`/api/jobs/${jobId}`);
        if (res.status === 404)
          throw new Error("Job not found, please submit again");
        const s = await readJson(res);
        if (stopped) return;
        setStatus(s);
        if (s.state === "DONE") {
          onReady(s.sha);
        } else if (s.state === "FAILED") {
          setError(s.error);
          setJobId(null);
        } else {
          timer = setTimeout(tick, 1000);
        }
      } catch (e) {
        if (stopped) return;
        setError(e.message);
        setJobId(null);
      }
    };

    tick();
    return () => {
      stopped = true;
      clearTimeout(timer);
    };
  }, [jobId, onReady]);

  const start = async (label, request) => {
    setError(null);
    setStatus({ stage: label, progress: null });
    setSubmitting(true);
    try {
      const body = await readJson(await request());
      if (body.state === "DONE") {
        onReady(body.sha);
      } else {
        setStatus(body);
        setJobId(body.jobId);
      }
    } catch (err) {
      setStatus(null);
      setError(err.message);
    } finally {
      setSubmitting(false);
    }
  };

  const submitUrl = (e) => {
    e.preventDefault();
    start("Contacting GitHub…", () =>
      fetch("/api/repos", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ url }),
      }),
    );
  };

  const submitFile = (file) => {
    if (!file) return;
    if (!/\.zip$/i.test(file.name))
      return setError("Please choose a .zip file");
    if (file.size > MAX_MB * 1024 * 1024)
      return setError(`File is larger than ${MAX_MB} MB`);
    const form = new FormData();
    form.append("file", file);
    start("Uploading…", () =>
      fetch("/api/uploads", { method: "POST", body: form }),
    );
  };

  const busy = submitting || jobId !== null;

  return (
    <main className="hero">
      <div className="hero-head">
        <Logo size={48} />
        <h1>Saptarishi</h1>
        <p>
          See how a codebase fits together: its dependencies, its hot spots, and
          where a newcomer should start.
        </p>
      </div>

      <section className="card">
        <label className="field-label" htmlFor="repo-url">
          GitHub repository
        </label>
        <form onSubmit={submitUrl} className="row">
          <input
            id="repo-url"
            value={url}
            onChange={(e) => setUrl(e.target.value)}
            placeholder="https://github.com/owner/repo"
            disabled={busy}
          />
          <button type="submit" disabled={busy || !url.trim()}>
            Analyse
          </button>
        </form>

        <div className="or">or</div>

        <div
          role="button"
          tabIndex={0}
          className={`dropzone${dragging ? " drag" : ""}${busy ? " busy" : ""}`}
          onClick={() => !busy && fileRef.current?.click()}
          onKeyDown={(e) =>
            e.key === "Enter" && !busy && fileRef.current?.click()
          }
          onDragOver={(e) => {
            e.preventDefault();
            if (!busy) setDragging(true);
          }}
          onDragLeave={() => setDragging(false)}
          onDrop={(e) => {
            e.preventDefault();
            setDragging(false);
            if (!busy) submitFile(e.dataTransfer.files[0]);
          }}
        >
          <strong>Drop a .zip of your project here</strong>
          <span>or click to choose · up to {MAX_MB} MB</span>
          <input
            ref={fileRef}
            type="file"
            accept=".zip,application/zip"
            hidden
            onChange={(e) => {
              submitFile(e.target.files[0]);
              e.target.value = "";
            }}
          />
        </div>

        {status && (
          <div className="progress-box">
            <progress value={status.progress ?? undefined} max={100} />
            <div>
              {status.progress == null
                ? status.stage
                : `${status.stage} (${status.progress}%)`}
            </div>
          </div>
        )}
        {error && (
          <p className="error" role="alert">
            {error}
          </p>
        )}
      </section>

      <ul className="features">
        <li>Dependency map</li>
        <li>Churn × complexity heat</li>
        <li>Call graph</li>
        <li>Where to start</li>
      </ul>
    </main>
  );
}
