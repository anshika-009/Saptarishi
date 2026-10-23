import { useEffect, useState } from "react";
import Editor from "@monaco-editor/react";

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
              color: "#1a56a0",
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
      <div style={{ padding: "8px 12px", borderBottom: "1px solid #eee" }}>
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
          <div style={{ fontSize: 12, color: "#666", margin: "4px 0 8px" }}>
            {data.tokens} tokens · complexity {data.complexity} · {data.loc}{" "}
            lines
          </div>
        )}
        {data && analysed && metrics && (
          <div
            style={{ fontSize: 12, color: "#666", margin: "0 0 8px" }}
            title="Blast radius: files that depend on this one, directly or through other files"
          >
            used by {metrics.fanIn} · uses {metrics.fanOut} · blast radius{" "}
            {metrics.blastRadius}
          </div>
        )}
        {data && !analysed && (
          <div style={{ fontSize: 12, color: "#a15c00", margin: "4px 0 8px" }}>
            {data.loc} lines · imports and complexity are not analysed for{" "}
            {data.language} yet
          </div>
        )}
        {data && analysed && heat && (
          <div
            style={{ fontSize: 12, color: "#c9631f", margin: "0 0 8px" }}
            title="Heat = how often the file changed x how complex it is, each ranked among the repository's recently changed files"
          >
            changed in {heat.commits} of the last {commitsExamined} commits ·
            heat {heat.heat}/100
          </div>
        )}
        {data && analysed && god && (
          <div
            style={{ fontSize: 12, color: "#8e24aa", margin: "0 0 8px" }}
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
              color="#2b7bd9"
              items={data.imports}
              onNavigate={onNavigate}
            />
            <Links
              title="← Imported by"
              color="#2a9d5c"
              items={data.importedBy}
              onNavigate={onNavigate}
            />
            {cycle && (
              <Links
                title="⟲ In a circular dependency with"
                color="#d93025"
                items={cycle.filter((p) => p !== path)}
                onNavigate={onNavigate}
              />
            )}
          </>
        )}
      </div>
      <div style={{ flex: 1, minHeight: 0 }}>
        {error && <p style={{ color: "crimson", padding: 12 }}>{error}</p>}
        {!data && !error && (
          <p style={{ padding: 12, color: "#666" }}>Loading…</p>
        )}
        {data && (
          <Editor
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
              minimap: { enabled: false },
              fontSize: 12,
              scrollBeyondLastLine: false,
              automaticLayout: true,
            }}
          />
        )}
      </div>
      {data?.truncated && (
        <div style={{ fontSize: 12, color: "#a15c00", padding: "4px 12px" }}>
          File truncated to the first 512 KB.
        </div>
      )}
    </div>
  );
}
