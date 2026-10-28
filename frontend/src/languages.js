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

// The languages that also have a method-level call graph (Used by / Uses / Methods).
export const FALLBACK_CALL_GRAPH = [
  "java",
  "python",
  "javascript",
  "typescript",
  "go",
  "csharp",
  "rust",
];

const FALLBACK = {
  analysed: new Set(FALLBACK_ANALYSED),
  callGraph: new Set(FALLBACK_CALL_GRAPH),
};

let pending = null;

// What the backend analyses, from GET /api/languages. Asked once per page load.
function loadSupport() {
  if (!pending) {
    pending = fetch("/api/languages")
      .then((r) => {
        if (!r.ok) throw new Error(String(r.status));
        return r.json();
      })
      .then((j) => ({
        analysed: new Set(
          Array.isArray(j.analysed) ? j.analysed : FALLBACK_ANALYSED,
        ),
        // an older backend does not send callGraph: it only had Java
        callGraph: new Set(Array.isArray(j.callGraph) ? j.callGraph : ["java"]),
      }))
      .catch(() => {
        pending = null; // try again next time
        return FALLBACK;
      });
  }
  return pending;
}

const same = (a, b) => a.size === b.size && [...a].every((l) => b.has(l));

// { analysed, callGraph }: the languages that have a parser, and those that also have a call graph.
export function useLanguageSupport() {
  const [support, setSupport] = useState(FALLBACK);
  useEffect(() => {
    let live = true;
    loadSupport().then((s) => {
      if (!live) return;
      // keep the old object when nothing changed, so the graph isn't rebuilt for nothing
      setSupport((cur) =>
        same(cur.analysed, s.analysed) && same(cur.callGraph, s.callGraph)
          ? cur
          : s,
      );
    });
    return () => {
      live = false;
    };
  }, []);
  return support;
}

export function useAnalysedLanguages() {
  return useLanguageSupport().analysed;
}
