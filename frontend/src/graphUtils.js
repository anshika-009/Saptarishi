// Converts our API response into Cytoscape "elements".
// Folders become parent nodes and files become children of their folder.
export function toElements({ nodes, edges }) {
  const els = [];
  const seenFolders = new Set();

  for (const n of nodes) {
    const parts = n.folder ? n.folder.split("/") : [];

    // Create every folder level once: "src", "src/app", "src/app/web"...
    for (let i = 0; i < parts.length; i++) {
      const path = parts.slice(0, i + 1).join("/");
      if (seenFolders.has(path)) continue;
      seenFolders.add(path);
      els.push({
        data: {
          id: "dir:" + path,
          label: parts[i],
          parent: i > 0 ? "dir:" + parts.slice(0, i).join("/") : undefined,
          kind: "folder",
        },
      });
    }

    els.push({
      data: {
        id: n.id,
        label: n.id.split("/").pop(),
        parent: n.folder ? "dir:" + n.folder : undefined,
        kind: "file",
        language: n.language,
        complexity: n.complexity,
        tokens: n.tokens,
      },
    });
  }

  for (const e of edges) {
    els.push({
      data: {
        id: e.source + "->" + e.target,
        source: e.source,
        target: e.target,
      },
    });
  }
  return els;
}
