import { useEffect, useRef, useState } from "react";

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
    <div style={{ maxWidth: 560, margin: "12vh auto 0", padding: "0 16px" }}>
      <h1 style={{ marginBottom: 16 }}>Saptarishi</h1>
      <form onSubmit={submitUrl} style={{ display: "flex", gap: 8 }}>
        <input
          value={url}
          onChange={(e) => setUrl(e.target.value)}
          placeholder="https://github.com/owner/repo"
          disabled={busy}
          style={{ flex: 1, padding: 8 }}
        />
        <button type="submit" disabled={busy || !url.trim()}>
          Analyse
        </button>
      </form>

      <div
        style={{
          textAlign: "center",
          color: "#888",
          margin: "12px 0",
          fontSize: 13,
        }}
      >
        or
      </div>

      <div
        role="button"
        tabIndex={0}
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
        style={{
          border: `2px dashed ${dragging ? "#2b7bd9" : "#bbb"}`,
          background: dragging ? "#eef3fb" : "transparent",
          borderRadius: 8,
          padding: "28px 16px",
          textAlign: "center",
          color: "#555",
          cursor: busy ? "default" : "pointer",
          opacity: busy ? 0.6 : 1,
        }}
      >
        Drop a .zip of your project here, or click to choose
        <div style={{ fontSize: 12, color: "#888", marginTop: 4 }}>
          Up to {MAX_MB} MB
        </div>
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
        <div style={{ marginTop: 16 }}>
          <progress
            value={status.progress ?? undefined}
            max={100}
            style={{ width: "100%" }}
          />
          <div style={{ fontSize: 13, color: "#555" }}>
            {status.progress == null
              ? status.stage
              : `${status.stage} (${status.progress}%)`}
          </div>
        </div>
      )}
      {error && <p style={{ color: "crimson" }}>{error}</p>}
    </div>
  );
}
