import { useEffect, useState } from "react";

const STEPS = [
  {
    title: "This is a map of your code",
    points: [
      ["dot", "Each dot is a source file. A bigger dot means more complex logic."],
      ["box", "Boxes are folders. Files sit inside the folder they live in."],
      ["out", "An arrow from A to B means A imports (uses) B."],
    ],
  },
  {
    title: "Click a file to inspect it",
    points: [
      ["out", "Black arrows lead to the files it uses."],
      ["in", "Red arrows come from the files that use it."],
      ["dot", "Its code opens on the right, with metrics and clickable links. Click empty space to reset."],
    ],
  },
  {
    title: "Switch on highlights to spot trouble",
    points: [
      ["cyc", "Circular imports: files that depend on each other in a loop. Hard to change safely."],
      ["god", "God files: huge, complex and connected to everything. The riskiest files."],
      ["heat", "Churn heat: files that change often and are complex."],
    ],
  },
  {
    title: "Not sure where to begin?",
    points: [
      ["dot", "“Where to start” suggests open issues and low-risk files for newcomers."],
      ["dot", "The “Repo health” card (bottom left) shows the score and why points were lost."],
      ["dot", "Search finds files and methods. Pick a method to see its call graph."],
    ],
  },
];

export default function Guide({ onClose }) {
  const [i, setI] = useState(0);
  const last = i === STEPS.length - 1;
  const step = STEPS[i];

  useEffect(() => {
    const key = (e) => {
      if (e.key === "Escape") onClose();
      if (e.key === "ArrowRight") setI((v) => Math.min(v + 1, STEPS.length - 1));
      if (e.key === "ArrowLeft") setI((v) => Math.max(v - 1, 0));
    };
    window.addEventListener("keydown", key);
    return () => window.removeEventListener("keydown", key);
  }, [onClose]);

  return (
    <div className="modal-backdrop" onMouseDown={onClose}>
      <div
        className="modal"
        role="dialog"
        aria-modal="true"
        aria-label="How to use Saptarishi"
        onMouseDown={(e) => e.stopPropagation()}
      >
        <div className="kicker">
          Quick tour · {i + 1} of {STEPS.length}
        </div>
        <h2>{step.title}</h2>
        <ul className="points">
          {step.points.map(([sw, text], k) => (
            <li key={k}>
              <i className={`sw ${sw}`} />
              <span>{text}</span>
            </li>
          ))}
        </ul>
        <div className="modal-foot">
          <div className="dots" aria-hidden="true">
            {STEPS.map((_, k) => (
              <i key={k} className={k === i ? "on" : ""} />
            ))}
          </div>
          <span className="spacer" />
          {i > 0 && <button onClick={() => setI(i - 1)}>Back</button>}
          <button
            className="primary"
            autoFocus
            onClick={() => (last ? onClose() : setI(i + 1))}
          >
            {last ? "Start exploring" : "Next"}
          </button>
        </div>
        <div className="keys">
          Shortcuts: <kbd>/</kbd> search · <kbd>Esc</kbd> clear selection ·{" "}
          <kbd>?</kbd> reopen this tour
        </div>
      </div>
    </div>
  );
}
