import { useEffect, useRef, useState } from "react";
import cytoscape from "cytoscape";
import { C } from "./Theme";

const STYLE = [
  { selector: "node", style: { "font-family": C.sans } },
  {
    selector: "node",
    style: {
      label: "data(label)",
      "font-size": 10,
      "text-valign": "bottom",
      "text-margin-y": 4,
      "text-background-color": C.surface,
      "text-background-opacity": 0.85,
      "text-background-padding": 2,
      "background-color": C.node,
      width: "mapData(complexity, 1, 30, 18, 44)",
      height: "mapData(complexity, 1, 30, 18, 44)",
    },
  },
  {
    selector: "node.root",
    style: {
      "background-color": C.accent2,
      "border-width": 3,
      "border-color": C.ink,
    },
  },
  {
    selector: "edge",
    style: {
      width: 1.5,
      "line-color": C.edge,
      "target-arrow-color": C.edge,
      "target-arrow-shape": "triangle",
      "curve-style": "bezier",
    },
  },
  { selector: "edge.guess", style: { "line-style": "dashed", opacity: 0.6 } },
];

const LAYOUT = {
  name: "fcose",
  animate: false,
  fit: true,
  padding: 30,
  nodeDimensionsIncludeLabels: true,
  idealEdgeLength: () => 90,
  nodeRepulsion: () => 9000,
};

export default function CallGraph({ sha, method, onOpen, onClose }) {
  const boxRef = useRef(null);
  const openRef = useRef(onOpen);
  const [depth, setDepth] = useState(2);
  const [dir, setDir] = useState("both");
  const [result, setResult] = useState({});
  const key = `${method}|${depth}|${dir}`;

  useEffect(() => {
    openRef.current = onOpen;
  });

  useEffect(() => {
    const ctrl = new AbortController();
    const qs = `method=${encodeURIComponent(method)}&depth=${depth}&dir=${dir}`;
    fetch(`/api/repos/${sha}/calls?${qs}`, { signal: ctrl.signal })
      .then((r) =>
        r.ok ? r.json() : Promise.reject(new Error("HTTP " + r.status)),
      )
      .then((data) => setResult({ key, data }))
      .catch(
        (e) => e.name !== "AbortError" && setResult({ key, error: e.message }),
      );
    return () => ctrl.abort();
  }, [sha, method, depth, dir, key]);

  const current = result.key === key ? result : {};
  const { data, error } = current;

  useEffect(() => {
    if (!data) return;
    const cy = cytoscape({
      container: boxRef.current,
      style: STYLE,
      layout: LAYOUT,
      elements: [
        ...data.nodes.map((n) => ({
          data: {
            id: n.id,
            label: `${n.type}.${n.name}`,
            path: n.path,
            line: n.line,
            complexity: n.complexity,
          },
          classes: n.id === data.root ? "root" : "",
        })),
        ...data.edges.map((e) => ({
          data: {
            id: `${e.caller}>${e.callee}`,
            source: e.caller,
            target: e.callee,
          },
          classes: e.guess ? "guess" : "",
        })),
      ],
    });
    cy.on("tap", "node", (evt) => {
      const d = evt.target.data();
      openRef.current({ id: d.id, path: d.path, line: d.line });
    });
    return () => cy.destroy();
  }, [data]);

  const rootNode = data?.nodes.find((n) => n.id === data.root);

  return (
    <div
      style={{
        position: "absolute",
        left: 8,
        right: 8,
        bottom: 8,
        height: "55%",
        zIndex: 4,
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
          gap: 8,
          alignItems: "center",
          padding: "6px 10px",
          borderBottom: "1px solid var(--line-soft)",
        }}
      >
        <strong
          style={{
            flex: 1,
            fontSize: 13,
            overflow: "hidden",
            textOverflow: "ellipsis",
            whiteSpace: "nowrap",
          }}
        >
          Call graph{rootNode ? ` · ${rootNode.type}.${rootNode.name}` : ""}
        </strong>
        <select value={dir} onChange={(e) => setDir(e.target.value)}>
          <option value="both">Callers and callees</option>
          <option value="callers">Callers (who uses it)</option>
          <option value="callees">Callees (what it uses)</option>
        </select>
        <select
          value={depth}
          onChange={(e) => setDepth(Number(e.target.value))}
        >
          {[1, 2, 3].map((d) => (
            <option key={d} value={d}>
              {d} level{d > 1 ? "s" : ""}
            </option>
          ))}
        </select>
        <button onClick={onClose} aria-label="Close call graph">
          ×
        </button>
      </div>
      <div style={{ flex: 1, minHeight: 0, position: "relative" }}>
        <div ref={boxRef} style={{ position: "absolute", inset: 0 }} />
        {!data && !error && (
          <p style={{ padding: 12, color: "var(--mute)" }}>Loading…</p>
        )}
        {error && (
          <p style={{ padding: 12, color: "crimson" }}>
            Could not load calls: {error}
          </p>
        )}
      </div>
      <div
        style={{
          fontSize: 11,
          color: "var(--mute)",
          padding: "4px 10px",
          borderTop: "1px solid var(--line-soft)",
        }}
      >
        Arrow = calls. Dashed = best guess from a unique method name. Click a
        node to open it and re-centre.
        {data?.truncated && " Showing the first 120 methods only."}
      </div>
    </div>
  );
}
