import { useEffect, useState } from "react";

export default function Home({ onReady }) {
  const [url, setUrl] = useState(
    "https://github.com/spring-projects/spring-petclinic",
  );
  const [submitting, setSubmitting] = useState(false);
  const [jobId, setJobId] = useState(null);
  const [status, setStatus] = useState(null);
  const [error, setError] = useState(null);

  useEffect(() => {
    if (!jobId) return;
    let stopped = false;
    let timer;

    const tick = async () => {
      try {
        const res = await fetch(`/api/jobs/${jobId}`);
        if (res.status === 404)
          throw new Error("Job not found, please submit again");
        if (!res.ok) throw new Error("HTTP " + res.status);
        const s = await res.json();
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

  const submit = async (e) => {
    e.preventDefault();
    setError(null);
    setStatus(null);
    setSubmitting(true);
    try {
      const res = await fetch("/api/repos", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ url }),
      });
      const body = await res.json();
      if (!res.ok) throw new Error(body.error || "HTTP " + res.status);
      if (body.state === "DONE") {
        onReady(body.sha);
      } else {
        setStatus(body);
        setJobId(body.jobId);
      }
    } catch (err) {
      setError(err.message);
    } finally {
      setSubmitting(false);
    }
  };

  const busy = submitting || jobId !== null;

  return (
    <div style={{ maxWidth: 560, margin: "15vh auto 0", padding: "0 16px" }}>
      <h1 style={{ marginBottom: 16 }}>Saptarishi</h1>
      <form onSubmit={submit} style={{ display: "flex", gap: 8 }}>
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
      {status && (
        <div style={{ marginTop: 16 }}>
          <progress
            value={status.progress}
            max={100}
            style={{ width: "100%" }}
          />
          <div style={{ fontSize: 13, color: "#555" }}>
            {status.stage} ({status.progress}%)
          </div>
        </div>
      )}
      {error && <p style={{ color: "crimson" }}>{error}</p>}
    </div>
  );
}
