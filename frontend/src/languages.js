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
  "kotlin",
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
      .then((j) => new Set(j.analysed))
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
    loadAnalysed().then((s) => live && setSet(s));
    return () => {
      live = false;
    };
  }, []);
  return set;
}
