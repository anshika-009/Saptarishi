import { useEffect, useRef, useState } from "react";
import cytoscape from "cytoscape";
import fcose from "cytoscape-fcose";
import { toElements } from "./graphUtils";
import CodePanel from "./CodePanel";

cytoscape.use(fcose);

const OUT = "#2b7bd9";
const IN = "#2a9d5c";

const style = [
  {
    selector: 'node[kind = "file"]',
    style: {
      label: "data(label)",
      color: "#222",
      "font-size": 11,
      "min-zoomed-font-size": 9,
      "text-valign": "bottom",
      "text-margin-y": 5,
      "text-background-color": "#ffffff",
      "text-background-opacity": 0.85,
      "text-background-padding": 2,
      "background-color": "#888",
      width: "mapData(complexity, 0, 40, 22, 56)",
      height: "mapData(complexity, 0, 40, 22, 56)",
    },
  },
  {
    selector: 'node[language = "java"]',
    style: { "background-color": "#d9822b" },
  },
  {
    selector: 'node[kind = "folder"]',
    style: {
      shape: "round-rectangle",
      label: "data(label)",
      color: "#666",
      "font-size": 12,
      "text-valign": "top",
      "text-halign": "center",
      "text-margin-y": -6,
      padding: 28,
      "background-color": "#9aa0a6",
      "background-opacity": 0.12,
      "border-width": 1,
      "border-color": "#b5b5b5",
    },
  },
  {
    selector: "edge",
    style: {
      width: 1.5,
      opacity: 0.7,
      "line-color": "#4a6fa5",
      "target-arrow-color": "#4a6fa5",
      "target-arrow-shape": "triangle",
      "curve-style": "bezier",
    },
  },
  { selector: ".dim", style: { opacity: 0.1 } },
  {
    selector: "node.sel",
    style: { "border-width": 3, "border-color": "#111" },
  },
  {
    selector: "edge.out",
    style: {
      "line-color": OUT,
      "target-arrow-color": OUT,
      width: 2.5,
      opacity: 1,
    },
  },
  {
    selector: "edge.in",
    style: {
      "line-color": IN,
      "target-arrow-color": IN,
      width: 2.5,
      opacity: 1,
    },
  },
];

const LAYOUTS = {
  clusters: {
    label: "Clusters (by folder)",
    flat: false,
    options: {
      name: "fcose",
      animate: false,
      fit: true,
      padding: 40,
      randomize: false,
      quality: "proof",
      nodeDimensionsIncludeLabels: true,
      nodeRepulsion: () => 60000,
      idealEdgeLength: () => 140,
      nestingFactor: 0.1,
      nodeSeparation: 160,
      packComponents: true,
    },
  },
  hierarchy: {
    label: "Hierarchy (entry points on top)",
    flat: true,
    options: {
      name: "breadthfirst",
      directed: true,
      spacingFactor: 1.2,
      padding: 40,
      animate: false,
    },
  },
};

export default function Atlas({ sha, onBack }) {
  const containerRef = useRef(null);
  const cyRef = useRef(null);
  const [data, setData] = useState(null);
  const [error, setError] = useState(null);
  const [hideTests, setHideTests] = useState(true);
  const [layoutName, setLayoutName] = useState("clusters");
  const [selection, setSelection] = useState(null); // { id, center }

  useEffect(() => {
    const ctrl = new AbortController();
    fetch(`/api/repos/${sha}/graph`, { signal: ctrl.signal })
      .then((r) => {
        if (!r.ok) throw new Error("HTTP " + r.status);
        return r.json();
      })
      .then(setData)
      .catch((e) => e.name !== "AbortError" && setError(e.message));
    return () => ctrl.abort();
  }, [sha]);

  useEffect(() => {
    if (!data) return;
    const { flat, options } = LAYOUTS[layoutName];
    let elements = toElements(data, { hideTests });
    if (flat) {
      elements = elements
        .filter((e) => e.data.kind !== "folder")
        .map((e) => ({ data: { ...e.data, parent: undefined } }));
    }
    const cy = cytoscape({
      container: containerRef.current,
      elements,
      style,
      layout: options,
    });
    cy.on("tap", 'node[kind = "file"]', (evt) =>
      setSelection({ id: evt.target.id(), center: false }),
    );
    cy.on("tap", (evt) => evt.target === cy && setSelection(null));
    cyRef.current = cy;
    return () => {
      cy.destroy();
      cyRef.current = null;
    };
  }, [data, hideTests, layoutName]);

  useEffect(() => {
    const cy = cyRef.current;
    if (!cy) return;
    const node = selection ? cy.getElementById(selection.id) : null;
    cy.batch(() => {
      cy.elements().removeClass("dim sel in out");
      if (!node || node.empty()) return;
      cy.elements('node[kind = "file"], edge').addClass("dim");
      node.closedNeighborhood().removeClass("dim");
      node.addClass("sel");
      node.outgoers("edge").addClass("out");
      node.incomers("edge").addClass("in");
    });
    if (selection?.center && node && !node.empty()) {
      cy.animate(
        { center: { eles: node }, zoom: Math.max(cy.zoom(), 1) },
        { duration: 300 },
      );
    }
  }, [selection, data, hideTests, layoutName]);

  const panelOpen = selection !== null;
  useEffect(() => {
    cyRef.current?.resize();
  }, [panelOpen]);

  const navigate = (id) => setSelection({ id, center: true });

  return (
    <div style={{ display: "flex", width: "100%", height: "100%" }}>
      <div
        style={{
          position: "relative",
          flex: 1,
          minWidth: 0,
          overflow: "hidden",
        }}
      >
        <div
          style={{
            position: "absolute",
            top: 8,
            left: 8,
            zIndex: 1,
            display: "flex",
            gap: 8,
            alignItems: "center",
          }}
        >
          <button onClick={onBack}>← New repo</button>
          <select
            value={layoutName}
            onChange={(e) => setLayoutName(e.target.value)}
          >
            {Object.entries(LAYOUTS).map(([key, l]) => (
              <option key={key} value={key}>
                {l.label}
              </option>
            ))}
          </select>
          <label
            style={{
              fontSize: 13,
              background: "#fff",
              padding: "4px 8px",
              borderRadius: 4,
              border: "1px solid #ddd",
            }}
          >
            <input
              type="checkbox"
              checked={!hideTests}
              onChange={(e) => setHideTests(!e.target.checked)}
            />{" "}
            Show tests
          </label>
        </div>
        {error && (
          <p
            style={{
              position: "absolute",
              top: 8,
              right: 8,
              zIndex: 1,
              color: "crimson",
              margin: 0,
            }}
          >
            Could not load graph: {error}
          </p>
        )}
        <div ref={containerRef} style={{ width: "100%", height: "100%" }} />
      </div>
      {selection && (
        <div
          style={{
            width: 480,
            borderLeft: "1px solid #ddd",
            background: "#fff",
          }}
        >
          <CodePanel
            sha={sha}
            path={selection.id}
            onNavigate={navigate}
            onClose={() => setSelection(null)}
          />
        </div>
      )}
    </div>
  );
}
