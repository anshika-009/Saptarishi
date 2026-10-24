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
        ƒ Methods ({res.methods.length}) · ← callers → callees
      </div>
      <div style={{ maxHeight: 110, overflow: "auto" }}>
        {res.methods.map((m) => (
          <button
            key={m.id}
            title="Show call graph"
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
              ← {m.callers} → {m.callees}
            </span>
          </button>
        ))}
      </div>
    </div>
  );
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
          <button onClick={onClose} aria-label="Close">
            ×
          </button>
        </div>
        {data && analysed && (
          <div
            style={{ fontSize: 12, color: "var(--mute)", margin: "4px 0 8px" }}
          >
            {data.tokens} tokens · complexity {data.complexity} · {data.loc}{" "}
            lines
          </div>
        )}
        {data && analysed && metrics && (
          <div
            style={{ fontSize: 12, color: "var(--mute)", margin: "0 0 8px" }}
            title="Blast radius: files that depend on this one, directly or through other files"
          >
            used by {metrics.fanIn} · uses {metrics.fanOut} · blast radius{" "}
            {metrics.blastRadius}
          </div>
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
              title="→ Imports"
              color="var(--out)"
              items={data.imports}
              onNavigate={onNavigate}
            />
            <Links
              title="← Imported by"
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
        {error && <p style={{ color: "crimson", padding: 12 }}>{error}</p>}
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
