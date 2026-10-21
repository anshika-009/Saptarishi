import { useEffect, useRef, useState } from "react";
import cytoscape from "cytoscape";
import fcose from "cytoscape-fcose";
import { toElements } from "./graphUtils";

cytoscape.use(fcose);

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
];

const layout = {
  name: "fcose",
  animate: false,
  fit: true,
  padding: 40,
  randomize: false, // deterministic: same graph gives same picture
  quality: "proof",
  nodeDimensionsIncludeLabels: true, // reserve room for labels so they collide less
  nodeRepulsion: () => 60000,
  idealEdgeLength: () => 140,
  nestingFactor: 0.1,
  nodeSeparation: 160,
  packComponents: true,
};

export default function Atlas() {
  const containerRef = useRef(null);
  const [data, setData] = useState(null);
  const [error, setError] = useState(null);
  const [hideTests, setHideTests] = useState(true);

  // Fetch once.
  useEffect(() => {
    let cancelled = false;
    fetch(
      "/api/dev/graph?url=https://github.com/spring-projects/spring-petclinic",
    )
      .then((r) => {
        if (!r.ok) throw new Error("HTTP " + r.status);
        return r.json();
      })
      .then((d) => !cancelled && setData(d))
      .catch((e) => !cancelled && setError(e.message));
    return () => {
      cancelled = true;
    };
  }, []);

  // Build the graph when data or the tests toggle changes.
  useEffect(() => {
    if (!data) return;
    const cy = cytoscape({
      container: containerRef.current,
      elements: toElements(data, { hideTests }),
      style,
      layout,
    });
    cy.on("tap", 'node[kind = "file"]', (evt) =>
      console.log("clicked", evt.target.id()),
    );
    return () => cy.destroy();
  }, [data, hideTests]);

  return (
    <div
      style={{
        position: "relative",
        width: "100%",
        height: "100%",
        overflow: "hidden",
      }}
    >
      <label
        style={{
          position: "absolute",
          top: 8,
          left: 8,
          zIndex: 1,
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
  );
}
