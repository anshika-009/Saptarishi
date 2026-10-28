import { useEffect, useState } from "react";

// Used until the backend answers, and if it never does, so the UI still works.
// Keep in step with the LanguageSupport classes on the backend.
export const FALLBACK_ANALYSED = [
  "java",
  "javascript",
  "typescript",
  "python",
  "go",
  "csharp",
  "rust",
  "c",
  "cpp",
  "ruby",
  "php",
  "html",
];

let pending = null;

// The languages that have a parser, from GET /api/languages. Asked once per page load.
function loadAnalysed() {
  if (!pending) {
    pending = fetch("/api/languages")
      .then((r) => {
        if (!r.ok) throw new Error(String(r.status));
        return r.json();
      })
      .then((j) => new Set(Array.isArray(j.analysed) ? j.analysed : FALLBACK_ANALYSED))
      .catch(() => {
        pending = null; // try again next time
        return new Set(FALLBACK_ANALYSED);
      });
  }
  return pending;
}

export function useAnalysedLanguages() {
  const [set, setSet] = useState(() => new Set(FALLBACK_ANALYSED));
  useEffect(() => {
    let live = true;
    loadAnalysed().then(
      (s) =>
        // keep the old Set when nothing changed, so the graph isn't rebuilt for nothing
        live &&
        setSet((old) =>
          old.size === s.size && [...s].every((l) => old.has(l)) ? old : s,
        ),
    );
    return () => {
      live = false;
    };
  }, []);
  return set;
}
