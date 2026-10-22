import { useEffect, useState } from "react";
import Editor from "@monaco-editor/react";

const name = (p) => p.split("/").pop();

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

export default function CodePanel({ sha, path, line, onNavigate, onClose }) {
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
        {data && (
          <div style={{ fontSize: 12, color: "#666", margin: "4px 0 8px" }}>
            {data.tokens} tokens · complexity {data.complexity} · {data.loc}{" "}
            lines
          </div>
        )}
        {data && (
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
