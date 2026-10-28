// Mirrors HealthScore.isTestPath on the backend: test folders plus each language's
// test-file naming convention.
export const isTest = (p) => {
  const path = "/" + p;
  if (/\/(tests?|__tests__)\//i.test(path)) return true;
  if (/\/[^/]*\.(unit|integration)?tests?\//i.test(path)) return true;
  const name = path.slice(path.lastIndexOf("/") + 1);
  return (
    /^.+\.(test|spec)\.[cm]?[jt]sx?$/.test(name) ||
    /^test_.+\.py$/.test(name) ||
    /^.+_test\.py$/.test(name) ||
    name === "conftest.py" ||
    name.endsWith("_test.go") ||
    /^(.*[a-z0-9])?Tests?\.cs$/.test(name) ||
    name === "tests.rs" ||
    name.endsWith("_test.rs") ||
    name.endsWith("_tests.rs")
  );
};

// "Foo.java" -> "Foo", "util.test.ts" -> "util.test"
export const stripExt = (name) => name.replace(/\.[^.]+$/, "");

// Converts the API response into Cytoscape elements.
// Folders become compound nodes. Chains of folders that hold no files and have a
// single child folder (src/main/java/org/...) are merged into one box.
export function toElements(
  { nodes, edges },
  { hideTests = true, analysedOnly = false, analysed = new Set() } = {},
) {
  const files = nodes.filter(
    (n) =>
      n.language !== "gomod" && // helper file (module path), not a source file
      !n.id.endsWith("package-info.java") &&
      !(analysedOnly && !analysed.has(n.language)) &&
      !(hideTests && isTest(n.id)),
  );
  const ids = new Set(files.map((n) => n.id));

  const directFiles = new Map(); // folder -> number of files directly inside
  const childFolders = new Map(); // folder -> Set of direct child folders
  for (const n of files) {
    directFiles.set(n.folder, (directFiles.get(n.folder) || 0) + 1);
    const parts = n.folder ? n.folder.split("/") : [];
    for (let i = 1; i <= parts.length; i++) {
      const parent = parts.slice(0, i - 1).join("/");
      if (!childFolders.has(parent)) childFolders.set(parent, new Set());
      childFolders.get(parent).add(parts.slice(0, i).join("/"));
    }
  }

  const isPass = (p) =>
    p !== "" && !directFiles.get(p) && childFolders.get(p)?.size === 1;

  // nearest visible folder strictly above `path` (or null)
  const visibleAncestor = (path) => {
    const parts = path.split("/");
    for (let i = parts.length - 1; i > 0; i--) {
      const p = parts.slice(0, i).join("/");
      if (!isPass(p)) return p;
    }
    return null;
  };
  // nearest visible folder at or above `path`
  const visibleAtOrAbove = (path) =>
    !path ? null : isPass(path) ? visibleAncestor(path) : path;

  const els = [];
  const seen = new Set();
  const depthOf = new Map(); // visible folder path -> nesting depth

  for (const n of files) {
    const parts = n.folder ? n.folder.split("/") : [];
    for (let i = 1; i <= parts.length; i++) {
      const path = parts.slice(0, i).join("/");
      if (isPass(path) || seen.has(path)) continue;
      seen.add(path);
      const parent = visibleAncestor(path);
      const depth = parent ? (depthOf.get(parent) ?? 0) + 1 : 0;
      depthOf.set(path, depth);
      els.push({
        data: {
          id: "dir:" + path,
          label: parent ? path.slice(parent.length + 1) : path,
          parent: parent ? "dir:" + parent : undefined,
          kind: "folder",
          depth,
        },
      });
    }
    const p = visibleAtOrAbove(n.folder);
    els.push({
      data: {
        id: n.id,
        label: stripExt(n.id.split("/").pop()),
        parent: p ? "dir:" + p : undefined,
        kind: "file",
        language: n.language,
        analysed: analysed.has(n.language),
        complexity: n.complexity,
        tokens: n.tokens,
        unparsed: n.parsed === false,
      },
    });
  }

  for (const e of edges) {
    if (ids.has(e.source) && ids.has(e.target)) {
      els.push({
        data: {
          id: e.source + "->" + e.target,
          source: e.source,
          target: e.target,
          etype: e.type,
        },
      });
    }
  }
  return els;
}
