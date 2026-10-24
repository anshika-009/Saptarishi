// Cytoscape and Monaco can't read CSS variables, so the same Beige & Ink palette lives here too.
export const C = {
  ink: "#17150f",
  mute: "#6b6454",
  surface: "#f8f3e8",
  line: "#d5cab3",
  node: "#8c8472",
  java: "#b89463",
  folder: "#9a8f78",
  folderLine: "#bfb291",
  edge: "#7a705c",
  out: "#17150f",
  inn: "#b4452a",
  accent2: "#b4452a",
  bad: "#8f2a14",
  god: "#6b3f69",
  sans: "Inter, system-ui, sans-serif",
};

export const FONT = { mono: '"JetBrains Mono", ui-monospace, monospace' };

// Pale sand (stable) to deep terracotta (changed often and complex). h is 0..100.
const STOPS = [
  [217, 205, 180],
  [201, 167, 124],
  [201, 112, 63],
  [163, 58, 30],
];

export function heatColor(h) {
  const t = (Math.max(0, Math.min(100, h)) / 100) * (STOPS.length - 1);
  const i = Math.min(Math.floor(t), STOPS.length - 2);
  const f = t - i;
  const m = (k) =>
    Math.round(STOPS[i][k] + (STOPS[i + 1][k] - STOPS[i][k]) * f);
  return `rgb(${m(0)}, ${m(1)}, ${m(2)})`;
}

export function defineMonaco(monaco) {
  monaco.editor.defineTheme("saptarishi", {
    base: "vs",
    inherit: true,
    rules: [
      { token: "comment", foreground: "8A8270", fontStyle: "italic" },
      { token: "keyword", foreground: "B4452A" },
      { token: "string", foreground: "5E6B2F" },
      { token: "number", foreground: "9C5A12" },
      { token: "type", foreground: "3F5A6B" },
      { token: "annotation", foreground: "6B3F69" },
    ],
    colors: {
      "editor.background": "#F4EEE0",
      "editor.foreground": "#17150F",
      "editorLineNumber.foreground": "#8A8270",
      "editorLineNumber.activeForeground": "#17150F",
      "editor.lineHighlightBackground": "#EBE2CE",
      "editor.selectionBackground": "#DCCFB2",
      "editorCursor.foreground": "#17150F",
      "editorIndentGuide.background1": "#E3D9C3",
    },
  });
}
