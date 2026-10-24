import { useEffect, useMemo, useRef, useState } from "react";
import cytoscape from "cytoscape";
import fcose from "cytoscape-fcose";
import { toElements, isTest } from "./graphUtils";
import CodePanel from "./CodePanel";
import SearchBox from "./SearchBox";
import HealthPanel from "./HealthPanel";
import CallGraph from "./CallGraph";
import Logo from "./Logo";
import { C, heatColor } from "./Theme";
import PathfinderPanel from "./PathfinderPanel";
import Guide from "./Guide";
import CyclePanel from "./CyclePanel";

function Chip({ on, onChange, children, title, swatch }) {
  return (
    <button
      type="button"
      className="chip"
      aria-pressed={on}
      title={title}
      onClick={() => onChange(!on)}
    >
      {swatch && <i className={`sw ${swatch}`} />}
      {children}
    </button>
  );
}

function Lg({ sw, children }) {
  return (
    <div className="lg">
      <span>
        <i className={`sw ${sw}`} />
      </span>
      <span>{children}</span>
    </div>
  );
}

cytoscape.use(fcose);

const OUT = C.out;
const IN = C.inn;
const GOD = C.god;

const style = [
  { selector: "node", style: { "font-family": C.sans } },
  {
    selector: 'node[kind = "file"]',
    style: {
      label: "data(label)",
      color: C.ink,
      "font-size": 11,
      "min-zoomed-font-size": 9,
      "text-valign": "bottom",
      "text-margin-y": 5,
      "text-background-color": C.surface,
      "text-background-opacity": 0.85,
      "text-background-padding": 2,
      "background-color": C.node,
      width: "mapData(complexity, 0, 40, 22, 56)",
      height: "mapData(complexity, 0, 40, 22, 56)",
    },
  },
  {
    selector: 'node[language = "java"]',
    style: { "background-color": C.java },
  },
  {
    selector: 'node[kind = "folder"]',
    style: {
      shape: "round-rectangle",
      label: "data(label)",
      color: C.mute,
      "font-size": 12,
      "text-valign": "top",
      "text-halign": "center",
      "text-margin-y": -6,
      padding: 28,
      "background-color": C.folder,
      "background-opacity": 0.12,
      "border-width": 1,
      "border-color": C.folderLine,
    },
  },
  {
    selector: "edge",
    style: {
      width: 1.5,
      opacity: 0.7,
      "line-color": C.edge,
      "target-arrow-color": C.edge,
      "target-arrow-shape": "triangle",
      "curve-style": "bezier",
    },
  },
  {
    selector: "node.cyc",
    style: { "border-width": 3, "border-color": C.bad },
  },
  {
    selector: "edge.cyc",
    style: {
      "line-color": C.bad,
      "target-arrow-color": C.bad,
      width: 2.5,
      opacity: 1,
    },
  },
  {
    selector: "node.god",
    style: { "border-width": 4, "border-color": GOD },
  },
  { selector: "node.heat", style: { "background-color": "data(heatColor)" } },
  { selector: ".hidden", style: { display: "none" } },
  { selector: ".dim", style: { opacity: 0.1 } },
  {
    selector: "node.sel",
    style: { "border-width": 3, "border-color": C.ink },
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
  const [health, setHealth] = useState(null); // { score, grade, factors, godFiles, ...
  const [showGods, setShowGods] = useState(true);
  const [showHeat, setShowHeat] = useState(false);
  const [showPath, setShowPath] = useState(false); // onboarding pathfinder drawer
  const [callRoot, setCallRoot] = useState(null); // method id shown in the call graph drawer
  const [churn, setChurn] = useState(null); // { available, reason, commitsExamined, files }
  const searchRef = useRef(null);
  const focusRef = useRef(null);
  const [focusTick, setFocusTick] = useState(0);
  const [showCycleList, setShowCycleList] = useState(false);
  const [guide, setGuide] = useState(() => {
    try {
      return !localStorage.getItem("saptarishi-guide");
    } catch {
      return false;
    }
  });
  const [hint, setHint] = useState(true);
  const [legend, setLegend] = useState(false);
  const [hover, setHover] = useState(null); // { id, complexity, x, y }
  const closeGuide = () => {
    setGuide(false);
    try {
      localStorage.setItem("saptarishi-guide", "1");
    } catch {
      /* storage unavailable */
    }
  };
  const languages = useMemo(
    () => [...new Set((data?.nodes ?? []).map((n) => n.language))].sort(),
    [data],
  );
  const stats = useMemo(() => {
    if (!data) return null;
    const by = {};
    for (const n of data.nodes) by[n.language] = (by[n.language] || 0) + 1;
    return {
      by,
      java: by.java || 0,
      total: data.nodes.length,
      edges: data.edges.length,
    };
  }, [data]);
  // Only Java is parsed, so other languages can never have arrows. Hide them by default
  // (unless there is no Java at all, where hiding would leave an empty map).
  const [showUnanalysed, setShowUnanalysed] = useState(false);
  const hideUnanalysed = !showUnanalysed && (stats?.java ?? 0) > 0;
  const metricsById = useMemo(
    () => new Map((analysis?.nodes ?? []).map((m) => [m.id, m])),
    [analysis],
  );
  const heatById = useMemo(
    () => new Map((churn?.files ?? []).map((f) => [f.path, f])),
    [churn],
  );
  const godById = useMemo(
    () => new Map((health?.godFiles ?? []).map((g) => [g.id, g])),
    [health],
  );
  // The map hides test files by default, so count only cycles it can actually show.
  const cycleCounts = useMemo(() => {
    const all = analysis?.cycles ?? [];
    return {
      all: all.length,
      prod: all.filter((c) => c.some((p) => !isTest(p))).length,
    };
  }, [analysis]);
  const shownCycles = hideTests ? cycleCounts.prod : cycleCounts.all;
  const hiddenCycles = hideTests ? cycleCounts.all - cycleCounts.prod : 0;
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
    const ctrl = new AbortController();
    fetch(`/api/repos/${sha}/health`, { signal: ctrl.signal })
      .then((r) => (r.ok ? r.json() : null))
      .then(setHealth)
      .catch((e) => e.name !== "AbortError" && setHealth(null));
    return () => ctrl.abort();
  }, [sha]);
  useEffect(() => {
    if (!showHeat || churn) return;
    const ctrl = new AbortController();
    fetch(`/api/repos/${sha}/churn`, { signal: ctrl.signal })
      .then((r) => {
        if (!r.ok) throw new Error("HTTP " + r.status);
        return r.json();
      })
      .then(setChurn)
      .catch(
        (e) =>
          e.name !== "AbortError" &&
          setChurn({
            available: false,
            reason: "Could not load churn data.",
            commitsExamined: 0,
            files: [],
          }),
      );
    return () => ctrl.abort();
  }, [showHeat, churn, sha]);
  useEffect(() => {
    if (!data) return;
    const { flat, options } = LAYOUTS[layoutName];
    let elements = toElements(data, {
      hideTests,
      analysedOnly: hideUnanalysed,
    });
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
    cy.on("tap", 'node[kind = "file"]', (evt) => {
      setHint(false);
      setHover(null);
      setSelection({ id: evt.target.id(), center: false, line: null });
    });
    cy.on("mouseover", 'node[kind = "file"]', (evt) => {
      const n = evt.target;
      setHover({
        id: n.id(),
        complexity: n.data("complexity"),
        x: n.renderedPosition().x,
        y: n.renderedBoundingBox().y1,
      });
      if (containerRef.current) containerRef.current.style.cursor = "pointer";
    });
    cy.on("mouseout", 'node[kind = "file"]', () => {
      setHover(null);
      if (containerRef.current) containerRef.current.style.cursor = "";
    });
    cy.on("pan zoom", () => setHover(null));
    cy.on("tap", (evt) => evt.target === cy && setSelection(null));
    cyRef.current = cy;
    return () => {
      cy.destroy();
      cyRef.current = null;
    };
  }, [data, hideTests, layoutName, hideUnanalysed]);

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
  }, [analysis, showCycles, data, hideTests, layoutName, hideUnanalysed]);
  useEffect(() => {
    const cy = cyRef.current;
    if (!cy) return;
    cy.batch(() => {
      cy.nodes().removeClass("heat");
      if (!showHeat || !churn?.available) return;
      cy.nodes('[kind = "file"]').forEach((n) => {
        const f = heatById.get(n.id());
        if (!f) return;
        n.data("heatColor", heatColor(f.heat));
        n.addClass("heat");
      });
    });
  }, [showHeat, churn, heatById, data, hideTests, layoutName, hideUnanalysed]);
  useEffect(() => {
    const cy = cyRef.current;
    if (!cy) return;
    cy.batch(() => {
      cy.nodes().removeClass("god");
      if (!showGods || !health) return;
      for (const g of health.godFiles) cy.getElementById(g.id).addClass("god");
    });
  }, [health, showGods, data, hideTests, layoutName, hideUnanalysed]);

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
  }, [selection, data, hideTests, layoutName, hideUnanalysed]);

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
  }, [lang, pathFilter, data, hideTests, layoutName, hideUnanalysed]);

  useEffect(() => {
    const cy = cyRef.current;
    const ids = focusRef.current;
    if (!cy || !ids) return;
    const els = cy.nodes().filter((n) => ids.includes(n.id()));
    if (els.empty()) return;
    focusRef.current = null;
    cy.animate({ fit: { eles: els, padding: 120 } }, { duration: 300 });
  }, [focusTick, data, hideTests, layoutName, hideUnanalysed]);

  const panelOpen = selection !== null;
  useEffect(() => {
    cyRef.current?.resize();
  }, [panelOpen]);

  useEffect(() => {
    const onKey = (e) => {
      if (/INPUT|SELECT|TEXTAREA/.test(e.target.tagName)) return;
      if (e.metaKey || e.ctrlKey) return;
      if (e.key === "/") {
        e.preventDefault();
        searchRef.current?.focus();
      } else if (e.key === "?") setGuide(true);
      else if (e.key === "Escape") setSelection(null);
    };
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, []);

  const focusCycle = (ids) => {
    setShowCycles(true);
    if (hideTests && ids.some(isTest)) setHideTests(false);
    focusRef.current = ids;
    setFocusTick((t) => t + 1);
  };

  const zoomBy = (f) => {
    const cy = cyRef.current;
    if (!cy) return;
    cy.zoom({
      level: cy.zoom() * f,
      renderedPosition: { x: cy.width() / 2, y: cy.height() / 2 },
    });
  };
  const fit = () => cyRef.current?.fit(undefined, 40);

  const navigate = (id, line = null) => {
    setHint(false);
    if (cyRef.current?.getElementById(id).empty()) {
      setHideTests(false);
      setShowUnanalysed(true);
    }
    setSelection({ id, center: true, line });
  };
  const openMethod = (m) => {
    navigate(m.path, m.line);
    setCallRoot(m.id);
  };

  return (
    <div className="atlas">
      <header className="topbar">
        <button
          className="brand"
          onClick={onBack}
          title="Back to start"
          aria-label="Back to start"
        >
          <Logo size={26} />
          <span>Saptarishi</span>
        </button>
        <SearchBox sha={sha} onPick={navigate} inputRef={searchRef} />
        <div className="seg" role="group" aria-label="Layout">
          {Object.entries(LAYOUTS).map(([key, l]) => (
            <button
              key={key}
              type="button"
              aria-pressed={layoutName === key}
              title={l.label}
              onClick={() => setLayoutName(key)}
            >
              {l.label.split(" (")[0]}
            </button>
          ))}
        </div>
        <span className="spacer" />
        <span className="sha" title={`Commit ${sha}`}>
          commit {sha.slice(0, 7)}
        </span>
        <button
          className="primary"
          onClick={() => {
            setShowCycleList(false);
            setShowPath((v) => !v);
          }}
        >
          {showPath ? "Hide suggestions" : "Where to start"}
        </button>
        <button
          className="icon-btn"
          onClick={() => setGuide(true)}
          aria-label="How to use this map"
          title="How to use this map (?)"
        >
          ?
        </button>
      </header>
      <div className="lensbar">
        <span className="lens-label">Highlight</span>
        <Chip
          on={showCycles}
          onChange={setShowCycles}
          swatch="cyc"
          title="Files that import each other in a loop. Hard to change or test in isolation."
        >
          Circular imports ({analysis ? shownCycles : "…"}
          {hiddenCycles > 0 ? ` · +${hiddenCycles} in tests` : ""})
        </Chip>
        {cycleCounts.all > 0 && (
          <button
            type="button"
            className="chip"
            aria-pressed={showCycleList}
            title="List every circular import and the files in it"
            onClick={() => {
              setShowPath(false);
              setShowCycleList((v) => !v);
            }}
          >
            See where
          </button>
        )}
        <Chip
          on={showGods}
          onChange={setShowGods}
          swatch="god"
          title="Files in the top 5% for size, complexity and connections at once. The riskiest to change."
        >
          God files ({health ? health.godFiles.length : "…"})
        </Chip>
        <Chip
          on={showHeat}
          onChange={setShowHeat}
          swatch="heat"
          title="Colour files by how often they change and how complex they are (reads git history)."
        >
          Churn heat{showHeat && churn === null ? " (loading…)" : ""}
        </Chip>
        <span className="vsep" />
        <span className="lens-label">Filter</span>
        <select
          value={lang}
          onChange={(e) => setLang(e.target.value)}
          aria-label="Language"
        >
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
          placeholder="Path contains… e.g. controller"
          aria-label="Filter files by path"
          style={{ width: 200 }}
        />
        <Chip
          on={!hideTests}
          onChange={(v) => setHideTests(!v)}
          title="Include files under test/ folders"
        >
          Show tests
        </Chip>
        {(lang !== "all" || pathFilter) && (
          <button
            type="button"
            className="chip"
            onClick={() => {
              setLang("all");
              setPathFilter("");
            }}
          >
            Clear filters
          </button>
        )}
      </div>
      {stats && stats.java < stats.total && (
        <div className="notice" role="note">
          <span>
            {stats.java === 0
              ? "No Java files were found, so there are no dependency arrows. Saptarishi only analyses Java for now; "
              : `Only Java files get dependency arrows. ${stats.total - stats.java} of ${stats.total} files are other languages (`}
            {Object.entries(stats.by)
              .filter(([l]) => l !== "java")
              .sort((x, y) => y[1] - x[1])
              .slice(0, 3)
              .map(([l, c]) => `${l} ${c}`)
              .join(", ")}
            {stats.java === 0
              ? " are shown as unconnected dots."
              : hideUnanalysed
                ? "), so they are hidden."
                : "), shown here as unconnected dots."}
          </span>
          {stats.java > 0 && (
            <button type="button" onClick={() => setShowUnanalysed((v) => !v)}>
              {hideUnanalysed ? "Show them" : "Hide them"}
            </button>
          )}
        </div>
      )}
      {stats && stats.java > 0 && stats.edges === 0 && (
        <div className="notice" role="alert">
          <span>
            {stats.java} Java files were found but no imports between them could
            be matched to files in this repository, so there are no arrows.
          </span>
        </div>
      )}
      <div className="atlas-body">
        <div className="canvas-wrap">
          {error && (
            <p
              style={{
                position: "absolute",
                top: 8,
                right: 8,
                zIndex: 1,
                color: "var(--bad)",
                margin: 0,
              }}
            >
              Could not load graph: {error}
            </p>
          )}
          <div ref={containerRef} className="graph" />
          {!data && !error && (
            <div className="loading" role="status">
              <span className="spinner" />
              Drawing the map…
            </div>
          )}
          {data && hint && !selection && (
            <div className="hint" role="note">
              <span>
                Click any file to see what it uses and what uses it. Scroll to
                zoom, drag to pan.
              </span>
              <button onClick={() => setHint(false)} aria-label="Dismiss tip">
                ×
              </button>
            </div>
          )}
          {hover && (
            <div className="tip" style={{ left: hover.x, top: hover.y }}>
              {hover.id.split("/").pop()}
              <small>complexity {hover.complexity} · click to open</small>
            </div>
          )}
          <div className="overlay-tl">
            <div className="zoom">
              <button
                onClick={() => zoomBy(1.25)}
                aria-label="Zoom in"
                title="Zoom in"
              >
                +
              </button>
              <button
                onClick={() => zoomBy(0.8)}
                aria-label="Zoom out"
                title="Zoom out"
              >
                −
              </button>
              <button
                onClick={fit}
                aria-label="Fit to screen"
                title="Fit to screen"
              >
                ⤢
              </button>
            </div>
            <button
              className="legend-toggle"
              aria-expanded={legend}
              onClick={() => setLegend((v) => !v)}
            >
              Legend {legend ? "▾" : "▸"}
            </button>
            {legend && (
              <div className="legend">
                <Lg sw="dot">File. Bigger = more complex.</Lg>
                <Lg sw="box">Folder.</Lg>
                <Lg sw="out">
                  Arrow: A imports B. Selected file: black = it uses, red = uses
                  it.
                </Lg>
                {showCycles && <Lg sw="cyc">Circular import loop.</Lg>}
                {showGods && (
                  <Lg sw="god">God file: big, complex, widely connected.</Lg>
                )}
                {showHeat && (
                  <>
                    <Lg sw="heat">
                      Pale = stable, deep red = changes often and complex.
                    </Lg>
                    {churn && (
                      <div className="legend-note">
                        {churn.available
                          ? `Based on the last ${churn.commitsExamined} commits.`
                          : churn.reason}
                      </div>
                    )}
                  </>
                )}
              </div>
            )}
          </div>
          <HealthPanel health={health} onNavigate={navigate} />
          {showCycleList && analysis && (
            <CyclePanel
              cycles={analysis.cycles}
              edges={data?.edges ?? []}
              onFocus={focusCycle}
              onOpen={navigate}
              onClose={() => setShowCycleList(false)}
            />
          )}
          {showPath && (
            <PathfinderPanel
              sha={sha}
              onNavigate={navigate}
              onClose={() => setShowPath(false)}
            />
          )}
          {callRoot && (
            <CallGraph
              sha={sha}
              method={callRoot}
              onOpen={openMethod}
              onClose={() => setCallRoot(null)}
            />
          )}
        </div>
        {selection && (
          <aside className="code-panel">
            <CodePanel
              sha={sha}
              path={selection.id}
              line={selection.line}
              metrics={metricsById.get(selection.id)}
              cycle={cycleOf.get(selection.id)}
              god={godById.get(selection.id)}
              heat={
                showHeat && churn?.available
                  ? heatById.get(selection.id)
                  : undefined
              }
              commitsExamined={churn?.commitsExamined}
              onNavigate={navigate}
              onMethod={openMethod}
              onClose={() => setSelection(null)}
            />
          </aside>
        )}
      </div>
      {guide && <Guide onClose={closeGuide} />}
    </div>
  );
}
