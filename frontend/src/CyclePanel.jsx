import { useMemo } from "react";
import { isTest, stripExt } from "./graphUtils";

const name = (p) =>
  stripExt(p.split("/").pop());
const dir = (p) => {
  const i = p.lastIndexOf("/");
  return i < 0 ? "" : p.slice(0, i);
};

// One concrete loop through a group (A -> B -> C -> A), found by breadth-first search,
// so people see the actual path rather than just an unordered set of files.
function findLoop(members, edges) {
  const set = new Set(members);
  const out = new Map();
  for (const e of edges) {
    if (!set.has(e.source) || !set.has(e.target) || e.source === e.target)
      continue;
    if (!out.has(e.source)) out.set(e.source, []);
    out.get(e.source).push(e);
  }
  let best = null;
  for (const start of members) {
    const prev = new Map();
    const seen = new Set([start]);
    const queue = [start];
    const pathTo = (node) => {
      const path = [];
      while (node !== start) {
        const e = prev.get(node);
        path.unshift(e);
        node = e.source;
      }
      return path;
    };
    let found = null;
    for (let qi = 0; qi < queue.length && !found; qi++) {
      const u = queue[qi];
      for (const e of out.get(u) ?? []) {
        if (e.target === start) {
          found = [...pathTo(u), e];
          break;
        }
        if (!seen.has(e.target)) {
          seen.add(e.target);
          prev.set(e.target, e);
          queue.push(e.target);
        }
      }
    }
    if (found && (!best || found.length < best.length)) best = found;
  }
  return best;
}

function Cycle({ c, onFocus, onOpen }) {
  const loopFiles = c.loop ? c.loop.map((e) => e.source) : [];
  const others = c.members.filter((m) => !loopFiles.includes(m));
  const usesRefs = c.loop?.some((e) => e.type === "REFERENCE");
  return (
    <div className="cycle">
      <div className="cycle-head">
        <strong>
          Loop {c.n} · {c.members.length} files
        </strong>
        <button type="button" onClick={() => onFocus(c.members)}>
          Show on map
        </button>
      </div>
      {c.loop && (
        <div className="loop" aria-label="Example loop">
          {[...loopFiles, loopFiles[0]].map((p, i) => (
            <span key={i} className="loop-step">
              {i > 0 && <span className="arrow">→</span>}
              <button
                type="button"
                className="fl"
                title={p}
                onClick={() => onOpen(p)}
              >
                {name(p)}
              </button>
            </span>
          ))}
        </div>
      )}
      <div className="cycle-dir">{dir(c.members[0])}</div>
      {others.length > 0 && (
        <div className="loop">
          <span className="arrow">Also in this group:</span>
          {others.map((p) => (
            <button
              key={p}
              type="button"
              className="fl"
              title={p}
              onClick={() => onOpen(p)}
            >
              {name(p)}
            </button>
          ))}
        </div>
      )}
      {usesRefs && (
        <div className="cycle-note">
          Some links are same-package use, which needs no import statement.
        </div>
      )}
    </div>
  );
}

export default function CyclePanel({
  cycles,
  edges,
  onFocus,
  onOpen,
  onClose,
}) {
  const items = useMemo(
    () =>
      cycles.map((members, i) => ({
        members,
        n: i + 1,
        loop: findLoop(members, edges),
        testsOnly: members.every(isTest),
      })),
    [cycles, edges],
  );
  const mine = items.filter((c) => !c.testsOnly);
  const tests = items.filter((c) => c.testsOnly);

  return (
    <div className="side-panel">
      <div className="side-head">
        <strong>Circular imports</strong>
        <button onClick={onClose} aria-label="Close">
          ×
        </button>
      </div>
      <div className="side-body">
        <p className="side-intro">
          Each loop is a group of files that depend on each other, directly or
          through other files, so none can be changed or tested alone. Counts
          explicit imports and same-package use.
        </p>
        {items.length === 0 && <p className="side-intro">None found.</p>}
        {mine.length > 0 && (
          <h3 className="side-sub">In your code ({mine.length})</h3>
        )}
        {mine.map((c) => (
          <Cycle key={c.n} c={c} onFocus={onFocus} onOpen={onOpen} />
        ))}
        {tests.length > 0 && (
          <h3 className="side-sub">In test files only ({tests.length})</h3>
        )}
        {tests.map((c) => (
          <Cycle key={c.n} c={c} onFocus={onFocus} onOpen={onOpen} />
        ))}
      </div>
    </div>
  );
}
