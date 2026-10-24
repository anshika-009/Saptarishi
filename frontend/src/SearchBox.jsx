import { useEffect, useRef, useState } from "react";

const split = (p) => {
  const i = p.lastIndexOf("/");
  return [p.slice(i + 1), i < 0 ? "" : p.slice(0, i)];
};

export default function SearchBox({ sha, onPick, inputRef }) {
  const [q, setQ] = useState("");
  const [res, setRes] = useState({ files: [], symbols: [] });
  const [open, setOpen] = useState(false);
  const [active, setActive] = useState(0);
  const boxRef = useRef(null);

  useEffect(() => {
    const term = q.trim();
    if (!term) return;
    const ctrl = new AbortController();
    const timer = setTimeout(() => {
      fetch(`/api/repos/${sha}/search?q=${encodeURIComponent(term)}`, {
        signal: ctrl.signal,
      })
        .then((r) =>
          r.ok ? r.json() : Promise.reject(new Error("HTTP " + r.status)),
        )
        .then((d) => {
          setRes(d);
          setActive(0);
        })
        .catch(() => {});
    }, 200);
    return () => {
      clearTimeout(timer);
      ctrl.abort();
    };
  }, [q, sha]);

  useEffect(() => {
    const close = (e) => !boxRef.current?.contains(e.target) && setOpen(false);
    document.addEventListener("mousedown", close);
    return () => document.removeEventListener("mousedown", close);
  }, []);

  const items = q.trim()
    ? [
        ...res.files.map((f) => {
          const [name, dir] = split(f.path);
          return {
            key: "f:" + f.path,
            type: "File",
            path: f.path,
            line: null,
            label: name,
            sub: dir,
          };
        }),
        ...res.symbols.map((s) => ({
          key: `s:${s.path}:${s.line}:${s.name}`,
          type: "Symbol",
          path: s.path,
          line: s.line,
          label: s.name,
          sub: `${s.kind.toLowerCase()} · ${split(s.path)[0]}:${s.line}`,
        })),
      ]
    : [];

  const pick = (item) => {
    if (!item) return;
    onPick(item.path, item.line);
    setOpen(false);
  };

  const onKeyDown = (e) => {
    if (e.key === "ArrowDown") {
      e.preventDefault();
      setActive((a) => Math.min(a + 1, items.length - 1));
    } else if (e.key === "ArrowUp") {
      e.preventDefault();
      setActive((a) => Math.max(a - 1, 0));
    } else if (e.key === "Enter") {
      pick(items[active]);
    } else if (e.key === "Escape") {
      setOpen(false);
    }
  };

  return (
    <div ref={boxRef} style={{ position: "relative" }}>
      <input
        ref={inputRef}
        aria-label="Search files and methods"
        value={q}
        onChange={(e) => {
          setQ(e.target.value);
          setOpen(true);
        }}
        onFocus={() => setOpen(true)}
        onKeyDown={onKeyDown}
        placeholder="Search files or methods…  ( / )"
        style={{ width: 280 }}
      />
      {open && items.length > 0 && (
        <div
          style={{
            position: "absolute",
            top: "100%",
            left: 0,
            width: 360,
            maxHeight: 340,
            overflow: "auto",
            background: "var(--surface)",
            border: "1px solid var(--line)",
            borderRadius: 8,
            boxShadow: "var(--shadow)",
            zIndex: 5,
          }}
        >
          {items.map((it, i) => (
            <div key={it.key}>
              {(i === 0 || items[i - 1].type !== it.type) && (
                <div
                  style={{
                    fontSize: 11,
                    color: "var(--mute)",
                    padding: "6px 10px 2px",
                  }}
                >
                  {it.type}s
                </div>
              )}
              <div
                onMouseDown={() => pick(it)}
                onMouseEnter={() => setActive(i)}
                style={{
                  padding: "4px 10px",
                  cursor: "pointer",
                  background: i === active ? "var(--surface2)" : "transparent",
                }}
              >
                <div style={{ fontSize: 13 }}>{it.label}</div>
                <div
                  style={{
                    fontSize: 11,
                    color: "var(--mute)",
                    overflow: "hidden",
                    textOverflow: "ellipsis",
                    whiteSpace: "nowrap",
                  }}
                >
                  {it.sub}
                </div>
              </div>
            </div>
          ))}
        </div>
      )}
    </div>
  );
}
