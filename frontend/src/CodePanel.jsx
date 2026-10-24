import { useEffect, useState } from "react";
import Editor from "@monaco-editor/react";
import { FONT, defineMonaco } from "./Theme";

const name = (p) => p.split("/").pop();

// Languages with a parser. Others are shown as plain files until their plugin exists.
const ANALYSED = new Set(["java"]);

function Links({ title, color, items, onNavigate }) {
  return (
    <div style={{ marginBottom: 6 }}>
      <div style={{ fontSize: 12, color, fontWeight: 600 }}>
        {title} ({items.length})
      </div>
      <div style={{ maxHeight: 84, overflow: "auto" }}>
        {items.map((p) => (
          <button
            key={p}
            title={p}
            onClick={() => onNavigate(p)}
            style={{
              display: "block",
              border: 0,
              background: "none",
              padding: "1px 0",
              fontSize: 12,
              color: "var(--link)",
              cursor: "pointer",
            }}
          >
            {name(p)}
          </button>
        ))}
      </div>
    </div>
  );
}

function Methods({ sha, path, onMethod }) {
  const [res, setRes] = useState({});

  useEffect(() => {
    const ctrl = new AbortController();
    fetch(`/api/repos/${sha}/methods?path=${encodeURIComponent(path)}`, {
      signal: ctrl.signal,
    })
      .then((r) =>
        r.ok ? r.json() : Promise.reject(new Error("HTTP " + r.status)),
      )
      .then((d) => setRes({ path, ...d }))
      .catch(() => {});
    return () => ctrl.abort();
  }, [sha, path]);

  if (res.path !== path) return null;
  if (!res.available) {
    return (
      <div style={{ fontSize: 12, color: "var(--mute)", marginBottom: 6 }}>
        Re-analyse this repository to enable the call graph.
      </div>
    );
  }
  if (res.methods.length === 0) return null;
  return (
    <div style={{ marginBottom: 6 }}>
      <div style={{ fontSize: 12, color: "var(--accent2)", fontWeight: 600 }}>
        ƒ Methods ({res.methods.length}) · click one for its call graph
      </div>
      <div style={{ maxHeight: 110, overflow: "auto" }}>
        {res.methods.map((m) => (
          <button
            key={m.id}
            title="Show call graph: who calls it and what it calls"
            onClick={() => onMethod({ id: m.id, path, line: m.line })}
            style={{
              display: "block",
              border: 0,
              background: "none",
              padding: "1px 0",
              fontSize: 12,
              color: "var(--link)",
              cursor: "pointer",
              textAlign: "left",
            }}
          >
            {m.type}.{m.name}({m.arity}){" "}
            <span style={{ color: "var(--mute)" }}>
              {m.callers} callers · calls {m.callees}
            </span>
          </button>
        ))}
      </div>
    </div>
  );
}

function insight(m, god, cycle) {
  if (god)
    return "A god file: big, complex and tied to many others. Changes here are risky.";
  if (cycle)
    return "Part of a circular dependency, so it can't be changed or tested in isolation.";
  if (!m) return null;
  if (m.blastRadius >= 15)
    return `Many files rely on this one: a change could ripple to ${m.blastRadius} files.`;
  if (m.fanIn === 0)
    return "Nothing imports this file. It is probably an entry point, or unused.";
  if (m.blastRadius <= 3)
    return "Low impact: few files depend on it, so it is a safe place to start.";
  return null;
}

export default function CodePanel({
  sha,
  path,
  line,
  metrics,
  cycle,
  god,
  heat,
  commitsExamined,
  onNavigate,
  onMethod,
  onClose,
}) {
  const [result, setResult] = useState({});

  useEffect(() => {
    const ctrl = new AbortController();
    fetch(`/api/repos/${sha}/file?path=${encodeURIComponent(path)}`, {
      signal: ctrl.signal,
    })
      .then(async (r) => {
        const body = await r.json().catch(() => ({}));
        if (!r.ok) throw new Error(body.error || "HTTP " + r.status);
        return body;
      })
      .then((data) => setResult({ path, data }))
      .catch(
        (e) => e.name !== "AbortError" && setResult({ path, error: e.message }),
      );
    return () => ctrl.abort();
  }, [sha, path]);

  const { data, error } = result.path === path ? result : {};
  const analysed = data ? ANALYSED.has(data.language) : false;

  return (
    <div style={{ display: "flex", flexDirection: "column", height: "100%" }}>
      <div
        style={{
          padding: "8px 12px",
          borderBottom: "1px solid var(--line-soft)",
        }}
      >
        <div
          style={{ display: "flex", justifyContent: "space-between", gap: 8 }}
        >
          <strong
            style={{ overflow: "hidden", textOverflow: "ellipsis" }}
            title={path}
          >
            {name(path)}
          </strong>
          <button onClick={onClose} aria-label="Close" title="Close (Esc)">
            ×
          </button>
        </div>
        {data && analysed && (
          <div className="tiles">
            <div className="tile" title="Lines of source code">
              <b>{data.loc}</b>
              <span>lines</span>
            </div>
            <div className="tile" title="Cyclomatic complexity: roughly the number of decision paths. Higher is harder to follow.">
              <b>{data.complexity}</b>
              <span>complexity</span>
            </div>
            {metrics && (
              <>
                <div className="tile" title="Files that import this one directly">
                  <b>{metrics.fanIn}</b>
                  <span>used by</span>
                </div>
                <div className="tile" title="Blast radius: files that depend on this one, directly or through other files">
                  <b>{metrics.blastRadius}</b>
                  <span>blast radius</span>
                </div>
              </>
            )}
          </div>
        )}
        {data && analysed && insight(metrics, god, cycle) && (
          <p className="insight">{insight(metrics, god, cycle)}</p>
        )}
        {data && !analysed && (
          <div
            style={{
              fontSize: 12,
              color: "var(--warn-text)",
              margin: "4px 0 8px",
            }}
          >
            {data.loc} lines · imports and complexity are not analysed for{" "}
            {data.language} yet
          </div>
        )}
        {data && analysed && heat && (
          <div
            style={{ fontSize: 12, color: "var(--bad)", margin: "0 0 8px" }}
            title="Heat = how often the file changed x how complex it is, each ranked among the repository's recently changed files"
          >
            changed in {heat.commits} of the last {commitsExamined} commits ·
            heat {heat.heat}/100
          </div>
        )}
        {data && analysed && god && (
          <div
            style={{ fontSize: 12, color: "var(--god)", margin: "0 0 8px" }}
            title="In the top 5% of this repository for size, complexity and connections at once"
          >
            ⚠ God file · {god.loc} lines, complexity {god.complexity},{" "}
            {god.fanIn + god.fanOut} connections
          </div>
        )}
        {data && analysed && (
          <>
            <Links
              title="→ Uses these files"
              color="var(--out)"
              items={data.imports}
              onNavigate={onNavigate}
            />
            <Links
              title="← Used by these files"
              color="var(--in)"
              items={data.importedBy}
              onNavigate={onNavigate}
            />
            {cycle && (
              <Links
                title="⟲ In a circular dependency with"
                color="var(--bad)"
                items={cycle.filter((p) => p !== path)}
                onNavigate={onNavigate}
              />
            )}
          </>
        )}
        {data && analysed && data.language === "java" && (
          <Methods sha={sha} path={path} onMethod={onMethod} />
        )}
      </div>
      <div style={{ flex: 1, minHeight: 0 }}>
        {error && <p style={{ color: "var(--bad)", padding: 12 }}>{error}</p>}
        {!data && !error && (
          <p style={{ padding: 12, color: "var(--mute)" }}>Loading…</p>
        )}
        {data && (
          <Editor
            theme="saptarishi"
            beforeMount={defineMonaco}
            key={`${path}:${line ?? ""}`}
            path={path}
            language={data.language}
            value={data.content}
            onMount={(editor) => {
              if (!line) return;
              editor.revealLineInCenter(line);
              editor.setPosition({ lineNumber: line, column: 1 });
            }}
            options={{
              readOnly: true,
              fontFamily: FONT.mono,
              renderLineHighlight: "none",
              minimap: { enabled: false },
              fontSize: 12,
              scrollBeyondLastLine: false,
              automaticLayout: true,
            }}
          />
        )}
      </div>
      {data?.truncated && (
        <div
          style={{
            fontSize: 12,
            color: "var(--warn-text)",
            padding: "4px 12px",
          }}
        >
          File truncated to the first 512 KB.
        </div>
      )}
    </div>
  );
}
