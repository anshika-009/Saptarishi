import { useState } from "react";

const GOD_COLOR = "var(--god)";

const GRADE_COLOR = {
  A: "var(--good)",
  B: "var(--good2)",
  C: "var(--warn-text)",
  D: "var(--accent2)",
  F: "var(--bad)",
};

const name = (p) => p.split("/").pop();

function Bar({ penalty, max }) {
  const lost = max === 0 ? 0 : (penalty / max) * 100;
  return (
    <div
      style={{ height: 5, background: "var(--line-soft)", borderRadius: 3 }}
      aria-hidden="true"
    >
      <div
        style={{
          width: `${lost}%`,
          height: "100%",
          borderRadius: 3,
          background: lost >= 66 ? "var(--bad)" : lost >= 33 ? "var(--warn)" : "var(--line-strong)",
        }}
      />
    </div>
  );
}

/**
 * Collapsible repository health card: the 0-100 score, why it lost points, and the god files.
 * Clicking a god file jumps to it in the graph.
 */
export default function HealthPanel({ health, onNavigate }) {
  const [open, setOpen] = useState(false);
  if (!health || health.grade === "n/a") return null;

  const color = GRADE_COLOR[health.grade] ?? "var(--mute)";

  return (
    <div
      style={{
        position: "absolute",
        left: 8,
        bottom: 8,
        zIndex: 2,
        width: open ? 300 : "auto",
        background: "var(--surface)",
        border: "1px solid var(--line)",
        borderRadius: 6,
        fontSize: 13,
        boxShadow: "0 1px 4px rgba(23,21,15,0.12)",
      }}
    >
      <button
        onClick={() => setOpen(!open)}
        aria-expanded={open}
        title="Repository health: 100 minus points lost to circular imports, god files, complexity and coupling"
        style={{
          display: "flex",
          alignItems: "center",
          gap: 8,
          width: "100%",
          border: 0,
          background: "none",
          padding: "6px 10px",
          cursor: "pointer",
          font: "inherit",
          textAlign: "left",
        }}
      >
        <span style={{ fontWeight: 700, fontSize: 18, color }}>
          {health.score}
        </span>
        <span style={{ color, fontWeight: 600 }}>{health.grade}</span>
        <span style={{ color: "var(--mute)", flex: 1 }}>Repo health</span>
        <span aria-hidden="true">{open ? "▾" : "▸"}</span>
      </button>

      {open && (
        <div
          style={{
            padding: "4px 10px 10px",
            borderTop: "1px solid var(--line-soft)",
            maxHeight: "55vh",
            overflow: "auto",
          }}
        >
          <div style={{ fontSize: 12, color: "var(--mute)", margin: "6px 0 0" }}>
            Starts at 100 and loses points for tangled imports, oversized
            files, complexity and coupling. A is 85 and up, F is under 40.
          </div>
          {health.factors.map((f) => (
            <div key={f.key} style={{ margin: "8px 0" }}>
              <div style={{ display: "flex", justifyContent: "space-between" }}>
                <span>{f.label}</span>
                <span style={{ color: f.penalty ? "var(--ink)" : "var(--good)" }}>
                  {f.penalty ? `−${f.penalty}` : "✓"}
                  <span style={{ color: "var(--mute)" }}> / {f.max}</span>
                </span>
              </div>
              <Bar penalty={f.penalty} max={f.max} />
              <div style={{ fontSize: 12, color: "var(--mute)", marginTop: 2 }}>
                {f.detail}
              </div>
            </div>
          ))}

          <div style={{ fontWeight: 600, margin: "12px 0 4px", color: GOD_COLOR }}>
            God files ({health.godFiles.length})
          </div>
          {health.godFiles.length === 0 && (
            <div style={{ fontSize: 12, color: "var(--mute)" }}>None found.</div>
          )}
          {health.godFiles.map((g) => (
            <button
              key={g.id}
              onClick={() => onNavigate(g.id)}
              title={`${g.id}\nused by ${g.fanIn}, uses ${g.fanOut}`}
              style={{
                display: "flex",
                justifyContent: "space-between",
                gap: 8,
                width: "100%",
                border: 0,
                background: "none",
                padding: "2px 0",
                fontSize: 12,
                color: "var(--link)",
                cursor: "pointer",
                textAlign: "left",
              }}
            >
              <span style={{ overflow: "hidden", textOverflow: "ellipsis" }}>
                {name(g.id)}
              </span>
              <span style={{ color: "var(--mute)", whiteSpace: "nowrap" }}>
                {g.loc} lines · cx {g.complexity}
              </span>
            </button>
          ))}

          <div style={{ fontSize: 11, color: "var(--mute)", marginTop: 10, lineHeight: 1.4 }}>
            A god file is in the top 5% of this repo for size, complexity and
            connections at once. Based on {health.files} source files
            {health.testFilesExcluded > 0
              ? `; ${health.testFilesExcluded} test files left out`
              : ""}
            . A heuristic, not a verdict.
          </div>
        </div>
      )}
    </div>
  );
}
