import { useEffect, useRef, useState } from "react";
import cytoscape from "cytoscape";
import fcose from "cytoscape-fcose";
import { toElements } from "./graphUtils";

cytoscape.use(fcose); // register the layout once

const style = [
  {
    selector: 'node[kind = "file"]',
    style: {
      label: "data(label)",
      color: "#222",
      "font-size": 11,
      "text-valign": "bottom",
      "text-margin-y": 5,
      "text-background-color": "#ffffff",
      "text-background-opacity": 0.85,
      "text-background-padding": 2,
      "background-color": "#888",
      width: "mapData(complexity, 0, 20, 24, 56)",
      height: "mapData(complexity, 0, 20, 24, 56)",
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
      "line-color": "#9a9a9a",
      "target-arrow-color": "#9a9a9a",
      "target-arrow-shape": "triangle",
      "curve-style": "bezier",
    },
  },
];

const layout = {
  name: "fcose",
  animate: false,
  fit: true,
  padding: 50,
  randomize: true,
  quality: "proof",
  nodeRepulsion: () => 14000,
  idealEdgeLength: () => 120,
  nestingFactor: 0.2,
  nodeSeparation: 100,
  packComponents: true,
};

export default function Atlas() {
  const containerRef = useRef(null);
  const [error, setError] = useState(null);

  useEffect(() => {
    let cy;
    let cancelled = false;

    fetch("/api/demo/graph")
      .then((r) => {
        if (!r.ok) throw new Error("HTTP " + r.status);
        return r.json();
      })
      .then((data) => {
        if (cancelled) return;
        cy = cytoscape({
          container: containerRef.current,
          elements: toElements(data),
          style,
          layout,
        });
        cy.on("tap", 'node[kind = "file"]', (evt) =>
          console.log("clicked", evt.target.id()),
        );
      })
      .catch((e) => !cancelled && setError(e.message));

    return () => {
      cancelled = true;
      cy?.destroy();
    };
  }, []);

  return (
    <>
      {error && (
        <p style={{ color: "crimson", padding: 8 }}>
          Could not load graph: {error}
        </p>
      )}
      <div ref={containerRef} style={{ width: "100%", height: "100%" }} />
    </>
  );
}
