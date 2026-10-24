import { useEffect, useState } from "react";

const COLORS = {
  EASY: "var(--good)",
  MODERATE: "var(--warn-text)",
  RISKY: "var(--bad)",
  UNKNOWN: "var(--mute)",
};
const name = (p) => p.split("/").pop();

function Chip({ text, color, title }) {
  return (
    <span
      title={title}
      style={{
        fontSize: 11,
        fontWeight: 600,
        color: "var(--surface)",
        background: color,
        borderRadius: 10,
        padding: "1px 8px",
        whiteSpace: "nowrap",
      }}
    >
      {text}
    </span>
  );
}

function FileButton({ path, title, suffix, onNavigate }) {
  return (
    <button
      title={title ?? path}
      onClick={() => onNavigate(path)}
      style={{
        border: "1px solid var(--line)",
        background: "var(--surface2)",
        borderRadius: 8,
        padding: "1px 6px",
        fontSize: 12,
        color: "var(--link)",
        cursor: "pointer",
      }}
    >
      {name(path)}
      {suffix}
    </button>
  );
}

function IssueCard({ issue, onNavigate }) {
  const color = COLORS[issue.difficulty] ?? COLORS.UNKNOWN;
  const blast = issue.blast;
  return (
    <div
      style={{ padding: "8px 0", borderBottom: "1px solid var(--line-soft)" }}
    >
      <div style={{ display: "flex", gap: 8, alignItems: "flex-start" }}>
        <Chip
          text={issue.difficulty}
          color={color}
          title="Based on how many files depend on the files it touches, their complexity and how often they change"
        />
        <a
          href={issue.url}
          target="_blank"
          rel="noreferrer"
          style={{ fontSize: 13, color: "var(--ink)", fontWeight: 600 }}
        >
          #{issue.number} {issue.title}
        </a>
      </div>
      <div style={{ fontSize: 11, color: "var(--mute)", margin: "3px 0 5px" }}>
        {issue.labels.join(" · ")}
        {issue.comments > 0 && ` · ${issue.comments} comments`}
        {blast.level !== "UNKNOWN" &&
          ` · a change could reach ${blast.dependents} other file${blast.dependents === 1 ? "" : "s"}`}
      </div>
      {issue.files.length === 0 ? (
        <div style={{ fontSize: 12, color: "var(--warn-text)" }}>
          Could not tell which files this issue touches.
        </div>
      ) : (
        <div style={{ display: "flex", flexWrap: "wrap", gap: 4 }}>
          {issue.files.map((f) => (
            <FileButton
              key={f.path}
              path={f.path}
              title={`${f.path}\n${f.confidence} confidence: ${f.reason}\n${f.dependents} dependents · complexity ${f.complexity}${f.hot ? " · changed often" : ""}`}
              suffix={
                f.confidence === "HIGH"
                  ? ""
                  : ` (${f.confidence.toLowerCase()})`
              }
              onNavigate={onNavigate}
            />
          ))}
        </div>
      )}
    </div>
  );
}

function StarterRow({ s, onNavigate }) {
  return (
    <div
      style={{ padding: "6px 0", borderBottom: "1px solid var(--line-soft)" }}
    >
      <FileButton path={s.path} onNavigate={onNavigate} />
      <div style={{ fontSize: 11, color: "var(--mute)", marginTop: 2 }}>
        {s.path}
        <br />
        used by {s.dependents} · uses {s.uses} · complexity {s.complexity} ·{" "}
        {s.loc} lines
      </div>
    </div>
  );
}

export default function PathfinderPanel({ sha, onNavigate, onClose }) {
  const [state, setState] = useState({ sha: null });
  const [tab, setTab] = useState(null);

  useEffect(() => {
    const ctrl = new AbortController();
    fetch(`/api/repos/${sha}/pathfinder`, { signal: ctrl.signal })
      .then((r) => {
        if (!r.ok) throw new Error("HTTP " + r.status);
        return r.json();
      })
      .then((data) => setState({ sha, data }))
      .catch(
        (e) => e.name !== "AbortError" && setState({ sha, error: e.message }),
      );
    return () => ctrl.abort();
  }, [sha]);

  const { data, error } = state.sha === sha ? state : {};
  const hasIssues = !!data?.available && data.issues.length > 0;
  const active = tab ?? (hasIssues ? "issues" : "starters");

  const tabButton = (key, label) => (
    <button
      onClick={() => setTab(key)}
      style={{
        border: 0,
        borderBottom:
          active === key ? "2px solid var(--link)" : "2px solid transparent",
        background: "none",
        padding: "4px 8px",
        fontWeight: active === key ? 700 : 400,
        cursor: "pointer",
      }}
    >
      {label}
    </button>
  );

  return (
    <div
      style={{
        position: "absolute",
        top: 12,
        right: 8,
        bottom: 8,
        width: 400,
        zIndex: 3,
        display: "flex",
        flexDirection: "column",
        background: "var(--surface)",
        border: "1px solid var(--line)",
        borderRadius: 10,
        boxShadow: "var(--shadow)",
      }}
    >
      <div
        style={{
          display: "flex",
          justifyContent: "space-between",
          alignItems: "center",
          padding: "8px 12px 0",
        }}
      >
        <strong>Where to start</strong>
        <button onClick={onClose} aria-label="Close">
          ×
        </button>
      </div>
      <div
        style={{
          padding: "0 12px",
          borderBottom: "1px solid var(--line-soft)",
        }}
      >
        {tabButton(
          "issues",
          `Issues${data?.available ? ` (${data.issues.length})` : ""}`,
        )}
        {tabButton(
          "starters",
          `Safe starter files${data ? ` (${data.starters.length})` : ""}`,
        )}
      </div>

      <div
        style={{
          flex: 1,
          minHeight: 0,
          overflow: "auto",
          padding: "0 12px 8px",
        }}
      >
        {error && <p style={{ color: "var(--bad)" }}>Could not load: {error}</p>}
        {!data && !error && (
          <p style={{ color: "var(--mute)" }}>Looking at open issues…</p>
        )}

        {data && active === "issues" && (
          <>
            {!data.available && (
              <p style={{ fontSize: 13, color: "var(--warn-text)" }}>
                {data.reason} Safe starter files still work, see the other tab.
              </p>
            )}
            {data.available && data.issues.length === 0 && (
              <p style={{ fontSize: 13, color: "var(--mute)" }}>
                No open &quot;good first issue&quot;, &quot;help wanted&quot; or
                unassigned issues were found. Try the safe starter files.
              </p>
            )}
            {data.available &&
              data.issues.map((i) => (
                <IssueCard key={i.number} issue={i} onNavigate={onNavigate} />
              ))}
            {hasIssues && (
              <p style={{ fontSize: 11, color: "var(--mute)" }}>
                Files are guessed from the issue text (paths, stack frames,
                class and method names), so check the confidence shown next to
                each one.
              </p>
            )}
          </>
        )}

        {data && active === "starters" && (
          <>
            <p style={{ fontSize: 12, color: "var(--mute)" }}>
              Files with some logic but few dependents, outside circular
              imports, god files and frequently changed code. Safest first.
            </p>
            {data.starters.length === 0 && (
              <p style={{ fontSize: 13, color: "var(--mute)" }}>
                No file qualified as a safe starter.
              </p>
            )}
            {data.starters.map((s) => (
              <StarterRow key={s.path} s={s} onNavigate={onNavigate} />
            ))}
          </>
        )}
      </div>
    </div>
  );
}
