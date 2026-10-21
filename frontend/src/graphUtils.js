// Converts the API response into Cytoscape elements.
// Folders become compound nodes. Chains of folders that hold no files and have a
// single child folder (src/main/java/org/...) are merged into one box.
export function toElements({ nodes, edges }, { hideTests = true } = {}) {
  const files = nodes.filter(
    (n) =>
      !n.id.endsWith("package-info.java") &&
      !(hideTests && /(^|\/)test\//.test(n.id)),
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

  for (const n of files) {
    const parts = n.folder ? n.folder.split("/") : [];
    for (let i = 1; i <= parts.length; i++) {
      const path = parts.slice(0, i).join("/");
      if (isPass(path) || seen.has(path)) continue;
      seen.add(path);
      const parent = visibleAncestor(path);
      els.push({
        data: {
          id: "dir:" + path,
          label: parent ? path.slice(parent.length + 1) : path,
          parent: parent ? "dir:" + parent : undefined,
          kind: "folder",
        },
      });
    }
    const p = visibleAtOrAbove(n.folder);
    els.push({
      data: {
        id: n.id,
        label: n.id
          .split("/")
          .pop()
          .replace(/\.java$/, ""),
        parent: p ? "dir:" + p : undefined,
        kind: "file",
        language: n.language,
        complexity: n.complexity,
        tokens: n.tokens,
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
        },
      });
    }
  }
  return els;
}
