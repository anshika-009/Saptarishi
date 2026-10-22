import { useEffect, useMemo, useRef, useState } from "react";
import cytoscape from "cytoscape";
import fcose from "cytoscape-fcose";
import { toElements } from "./graphUtils";
import CodePanel from "./CodePanel";
import SearchBox from "./SearchBox";

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
  {
    selector: "node.cyc",
    style: { "border-width": 3, "border-color": "#d93025" },
  },
  {
    selector: "edge.cyc",
    style: {
      "line-color": "#d93025",
      "target-arrow-color": "#d93025",
      width: 2.5,
      opacity: 1,
    },
  },
  { selector: ".hidden", style: { display: "none" } },
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
  const [selection, setSelection] = useState(null); // { id, center, line }
  const [lang, setLang] = useState("all");
  const [pathFilter, setPathFilter] = useState("");
  const [analysis, setAnalysis] = useState(null); // { cycles, nodes }
  const [showCycles, setShowCycles] = useState(true);
  const languages = useMemo(
    () => [...new Set((data?.nodes ?? []).map((n) => n.language))].sort(),
    [data],
  );
  const metricsById = useMemo(
    () => new Map((analysis?.nodes ?? []).map((m) => [m.id, m])),
    [analysis],
  );
  const cycleOf = useMemo(() => {
    const map = new Map();
    for (const cycle of analysis?.cycles ?? []) {
      for (const id of cycle) map.set(id, cycle);
    }
    return map;
  }, [analysis]);

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
    const ctrl = new AbortController();
    fetch(`/api/repos/${sha}/analysis`, { signal: ctrl.signal })
      .then((r) => (r.ok ? r.json() : null))
      .then(setAnalysis)
      .catch((e) => e.name !== "AbortError" && setAnalysis(null));
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
      setSelection({ id: evt.target.id(), center: false, line: null }),
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
    cy.batch(() => {
      cy.elements().removeClass("cyc");
      if (!showCycles || !analysis) return;
      for (const cycle of analysis.cycles) {
        const members = new Set(cycle);
        cycle.forEach((id) => cy.getElementById(id).addClass("cyc"));
        cy.edges()
          .filter(
            (e) =>
              members.has(e.data("source")) && members.has(e.data("target")),
          )
          .addClass("cyc");
      }
    });
  }, [analysis, showCycles, data, hideTests, layoutName]);

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

  useEffect(() => {
    const cy = cyRef.current;
    if (!cy) return;
    const f = pathFilter.trim().toLowerCase();
    cy.batch(() => {
      cy.elements().removeClass("hidden");
      cy.nodes('[kind = "file"]')
        .filter(
          (n) =>
            (lang !== "all" && n.data("language") !== lang) ||
            (f && !n.id().toLowerCase().includes(f)),
        )
        .addClass("hidden");
      cy.nodes('[kind = "folder"]').forEach((folder) => {
        if (folder.descendants('[kind = "file"]').not(".hidden").empty())
          folder.addClass("hidden");
      });
    });
  }, [lang, pathFilter, data, hideTests, layoutName]);

  const panelOpen = selection !== null;
  useEffect(() => {
    cyRef.current?.resize();
  }, [panelOpen]);

  const navigate = (id, line = null) => {
    if (cyRef.current?.getElementById(id).empty()) setHideTests(false);
    setSelection({ id, center: true, line });
  };

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
            zIndex: 2,
            display: "flex",
            flexWrap: "wrap",
            gap: 8,
            alignItems: "center",
            maxWidth: "calc(100% - 16px)",
          }}
        >
          <button onClick={onBack}>← New repo</button>
          <SearchBox sha={sha} onPick={navigate} />
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
          <select value={lang} onChange={(e) => setLang(e.target.value)}>
            <option value="all">All languages</option>
            {languages.map((l) => (
              <option key={l} value={l}>
                {l}
              </option>
            ))}
          </select>
          <input
            value={pathFilter}
            onChange={(e) => setPathFilter(e.target.value)}
            placeholder="Filter by path…"
            style={{ width: 130, padding: "5px 8px" }}
          />
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
              checked={showCycles}
              onChange={(e) => setShowCycles(e.target.checked)}
            />{" "}
            Circular imports ({analysis ? analysis.cycles.length : "…"})
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
            line={selection.line}
            metrics={metricsById.get(selection.id)}
            cycle={cycleOf.get(selection.id)}
            onNavigate={navigate}
            onClose={() => setSelection(null)}
          />
        </div>
      )}
    </div>
  );
}
