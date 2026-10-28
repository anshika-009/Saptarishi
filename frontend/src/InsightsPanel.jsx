import { useState } from "react";
import { isTest, stripExt } from "./graphUtils";
import { heatColor } from "./Theme";

const TOP = 25;
const name = (p) =>
  stripExt(p.split("/").pop());
const dir = (p) => {
  const i = p.lastIndexOf("/");
  return i < 0 ? "" : p.slice(0, i);
};

function Row({ i, path, dot, children, onOpen }) {
  return (
    <button
      type="button"
      className="rank"
      title={path}
      onClick={() => onOpen(path)}
    >
      <span className="rank-n">{i + 1}</span>
      <span className="rank-main">
        <strong>
          {dot && <i className="dot-heat" style={{ background: dot }} />}
          {name(path)}
        </strong>
        <small>{dir(path)}</small>
        <small>{children}</small>
      </span>
    </button>
  );
}

export default function InsightsPanel({
  churn,
  analysis,
  hideTests,
  onOpen,
  onClose,
}) {
  const [tab, setTab] = useState("hot");
  const hot = (churn?.files ?? []).filter((f) => f.heat > 0).slice(0, TOP);
  const deps = [...(analysis?.nodes ?? [])]
    .filter((n) => n.blastRadius > 0 && !(hideTests && isTest(n.id)))
    .sort((a, b) => b.blastRadius - a.blastRadius || b.fanIn - a.fanIn)
    .slice(0, TOP);

  return (
    <div className="side-panel">
      <div className="side-head">
        <strong>Rankings</strong>
        <button onClick={onClose} aria-label="Close">
          ×
        </button>
      </div>
      <div style={{ padding: "8px 12px 0" }}>
        <div className="seg" role="group" aria-label="Ranking">
          <button
            type="button"
            aria-pressed={tab === "hot"}
            onClick={() => setTab("hot")}
          >
            Hot spots
          </button>
          <button
            type="button"
            aria-pressed={tab === "deps"}
            onClick={() => setTab("deps")}
          >
            Most depended on
          </button>
        </div>
      </div>
      <div className="side-body">
        {tab === "hot" && (
          <>
            <p className="side-intro">
              Files that change often <em>and</em> are complex, where bugs tend
              to cluster.
              {churn?.available &&
                ` Based on the last ${churn.commitsExamined} commits.`}
            </p>
            {!churn && <p className="side-intro">Reading git history…</p>}
            {churn && !churn.available && (
              <p className="side-intro">{churn.reason}</p>
            )}
            {churn?.available && hot.length === 0 && (
              <p className="side-intro">
                No recently changed files with logic were found.
              </p>
            )}
            {hot.map((f, i) => (
              <Row
                key={f.path}
                i={i}
                path={f.path}
                dot={heatColor(f.heat)}
                onOpen={onOpen}
              >
                heat {f.heat}/100 · changed in {f.commits} commits · complexity{" "}
                {f.complexity} · {f.loc} lines
              </Row>
            ))}
          </>
        )}
        {tab === "deps" && (
          <>
            <p className="side-intro">
              Files the most other files rely on, directly or through other
              files. A change here can ripple the furthest.
            </p>
            {deps.length === 0 && (
              <p className="side-intro">Loading, or nothing found.</p>
            )}
            {deps.map((n, i) => (
              <Row key={n.id} i={i} path={n.id} onOpen={onOpen}>
                blast radius {n.blastRadius} · used by {n.fanIn} · uses{" "}
                {n.fanOut}
              </Row>
            ))}
          </>
        )}
      </div>
    </div>
  );
}
