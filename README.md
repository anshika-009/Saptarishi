# Saptarishi

Paste a GitHub repository URL and get an interactive map of its code: files grouped by folder, import dependencies drawn as edges, and a read-only code panel with per-file metrics.

Currently supports **Java**. JavaScript, TypeScript and Python are planned.

## How it works

```
GitHub URL
   │  resolve latest commit SHA
   ▼
cached in Postgres for this SHA? ── yes ──► done (no download)
   │ no
   ▼
download zip ► safe extract + filter ► parse (JavaParser) ► resolve imports ► save
   │
   ▼
GET /api/repos/{sha}/graph  ──►  Cytoscape graph  +  Monaco code panel
```

- Analysis runs as a background job. The UI polls its status and shows a progress bar.
- Results are keyed by commit SHA, so analysing the same commit twice is instant.
- Extracted sources are kept on disk (`~/saptarishi-data`) so the code panel can serve files.

## Tech stack

| Layer    | Tools                                                      |
| -------- | ---------------------------------------------------------- |
| Backend  | Java 25, Spring Boot 4.1, JavaParser, Spring JDBC, Flyway  |
| Database | PostgreSQL 16                                              |
| Frontend | React 19, Vite, Cytoscape.js (fcose layout), Monaco editor |
| Tests    | JUnit 5, Mockito, Testcontainers                           |

## Getting started

**Requirements:** JDK 25, Node.js, Docker.

```powershell
# 1. Database (from the repo root). Listens on host port 5433.
docker compose up -d

# 2. Backend (terminal 1)
cd backend
./mvnw spring-boot:run

# 3. Frontend (terminal 2)
cd frontend
npm install
npm run dev
```

Open http://localhost:5173, paste a repository URL and click **Analyse**.

The frontend dev server proxies `/api` to `http://localhost:8080`, so the backend must be running.

### Configuration

Set in `backend/src/main/resources/application.properties` or as environment variables.

| Setting                   | Default                     | Purpose                                                                        |
| ------------------------- | --------------------------- | ------------------------------------------------------------------------------ |
| `GITHUB_TOKEN`            | empty                       | Raises GitHub's API rate limit. Without it you get about 60 requests per hour. |
| `DB_USER` / `DB_PASSWORD` | `saptarishi` / `saptarishi` | Database credentials (local dev defaults).                                     |
| `saptarishi.storage-dir`  | `~/saptarishi-data`         | Where downloaded repositories are extracted.                                   |
| `saptarishi.jobs.workers` | `2`                         | Number of analyses that run at once.                                           |

Do not use the default credentials outside local development.

## Using the Atlas

- **Folders** are drawn as boxes around their files. Chains of empty single-child folders are merged into one box.
- **Node size** reflects cyclomatic complexity.
- **Click a file** to open the code panel and highlight its neighbours. Blue edges are files it imports, and green edges are files that import it. Click the background to clear the selection.
- **Code panel** shows read-only source with syntax highlighting, token count, complexity, line count, and clickable _Imports_ and _Imported by_ lists.
- **Layouts:** _Clusters_ groups files by folder. _Hierarchy_ arranges them top-down with entry points (files nobody imports) on top.
- **Search:** the top bar searches file paths and symbol names (classes, interfaces, methods). Picking a result selects and centres that node. Picking a symbol also opens the file at its line.
- **Filters:** a language dropdown and a path filter (for example `controller`) hide non-matching files without re-running the layout. Folders left empty are hidden too.
- **Show tests** includes or hides files under `test/`. Picking a test file from search switches it on automatically.
- **Circular imports** are outlined in red. **God files** are outlined in purple (see below). Both can be switched off with their checkboxes.
- **Health card** (bottom left): the repository's score, why it lost points, and its god files. Click a god file to jump to it.

## Health score and god files

Both are computed on the fly from the stored graph. Test files are left out, because their imports would inflate the connectivity of the classes they exercise, and `package-info.java` and `module-info.java` are ignored.

**God file.** A file that is in the top 5% of its own repository for all three of size (lines), complexity (cyclomatic, summed over its methods) and connectivity (files that import it plus files it imports), and that also clears absolute floors: 300 lines, complexity 40 and 10 connections. Each factor is turned into a percentile rank, and the god score is `100 × size × complexity × connectivity` (each as a 0 to 1 rank), so a file has to be high on all three to score high. A large complex file that nothing else touches is not a god file, and neither is a heavily imported constants file. Because the rule is relative, a repository can have at most about 5% god files.

**Health score.** 100 minus four capped penalties, shown as a breakdown:

| Factor           | Max loss | Rule                                                                      |
| ---------------- | -------- | ------------------------------------------------------------------------- |
| Circular imports | 25       | `ceil(60 × share of files that sit in a cycle)`                           |
| God files        | 25       | 4 points per god file                                                     |
| Complexity       | 25       | Complexity per line of code: 0 points at 0.15 or below, full loss at 0.40 |
| Coupling         | 25       | Average number of files each file imports: 0 points at 3, full loss at 10 |

Grades: A 85 and above, B 70, C 55, D 40, F below.

The thresholds are a judgement call, tuned on a few real Java codebases (React Native's Android layer and its sub-packages, Capacitor, Cordova). On those the score ranged from the 50s to the low 90s, and well-factored packages scored higher than tangled ones. The complexity and coupling thresholds are the least tested, since those repositories were all similar on those two measures. The score is a heuristic for comparing and for finding things to look at, not a measurement of quality. All constants are at the top of `HealthScore.java`.

## API

| Method | Path                          | Description                                                                                                                                |
| ------ | ----------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------ |
| `POST` | `/api/repos`                  | Body `{"url": "..."}`. Starts an analysis. Returns `200` with a finished job if the commit is already cached, otherwise `202`.             |
| `GET`  | `/api/jobs/{id}`              | Job state: `jobId`, `sha`, `state` (`QUEUED`, `RUNNING`, `DONE`, `FAILED`), `stage`, `progress`, `error`.                                  |
| `GET`  | `/api/repos/{sha}/graph`      | The stored graph: `nodes` (path, folder, language, complexity, tokens, loc) and `edges`.                                                   |
| `GET`  | `/api/repos/{sha}/file?path=` | Source text plus metrics, `imports` and `importedBy`. `404` if unknown, `410` if the cached source was removed.                            |
| `GET`  | `/api/repos/{sha}/analysis`   | Circular-import groups, plus per-file fan-in, fan-out and blast radius.                                                                    |
| `GET`  | `/api/repos/{sha}/health`     | Health score (0 to 100), grade, the four-factor breakdown and the god files. Test files are excluded.                                      |
| `GET`  | `/api/repos/{sha}/search?q=`  | Case-insensitive substring search. Returns up to 8 `files` and 12 `symbols` (name, kind, line, path). Exact and prefix matches rank first. |

Errors return `{"error": "..."}`: `400` for a bad URL or a missing or private repository, `422` for an unsafe archive, `502` for GitHub or network failures, and `503` when the job queue is full.

Development-only endpoints under `/api/dev/**` and a demo graph exist for debugging and are not part of the stable API.

## Project structure

```
backend/src/main/java/com/saptarishi/backend/
├── api/      REST controllers and error handling
├── ingest/   GitHub client, safe unzip, file filter, per-SHA extraction cache
├── parse/    Language support (Java today), parser registry, parsed-file model
├── graph/    Import resolution, graph building, PostgreSQL persistence
├── job/      Background jobs and progress tracking
├── file/     Source and metrics for the code panel
└── search/   File and symbol search
backend/src/main/resources/db/migration/   Flyway schema (V1)
frontend/src/   Home, Atlas, CodePanel, SearchBox, graph element builder
```

### Database

One migration (`V1__graph_schema.sql`) creates:

- `repo_snapshot`: one row per analysed commit SHA.
- `source_file`: path, folder, language, tokens, complexity, loc.
- `symbol`: classes, interfaces and methods with line numbers (indexed by name for search).
- `edge`: resolved import edges, indexed on the target for "imported by".

Deleting a snapshot removes all of its rows.

## Safety limits

Downloaded archives are untrusted. Extraction enforces:

- at most 50,000 entries, 500 MB total, 100 MB per entry
- files over 1 MB are not kept
- no path traversal, no absolute paths, no null bytes in names
- excluded folders: `.git`, `node_modules`, `target`, `build`, `dist`, `vendor` and similar
- downloads are capped at 200 MB

The file endpoint only serves paths stored in the database, validates the SHA format, resolves paths inside the repository's source folder, and truncates files over 512 KB.

## Tests

```powershell
cd backend
./mvnw test
```

Docker must be running: the database tests start PostgreSQL through Testcontainers. The suite covers URL parsing, safe extraction, the Java parser, import resolution, graph persistence, graph analysis, the health score, the file endpoint and search.

## Known limitations

- Search covers source files in the graph only, not text files.
- Only Java is parsed. Other source languages appear in the file index but not in the graph.
- Only imports that resolve to files inside the repository become edges. Library imports are ignored.
- Public GitHub repositories only.
- Jobs are kept in memory, so they are lost on restart. Finished graphs stay in the database.
- If `~/saptarishi-data` is deleted, the graph still loads but the code panel returns `410` until the repository is analysed again.
- The Monaco editor loads from a CDN, so the code panel needs an internet connection.

## Roadmap

- [x] Skeleton: Spring Boot and React drawing a graph with Cytoscape
- [x] Ingestion: download, safe extract, filter
- [x] Java parser: imports, symbols, complexity, tokens
- [x] Resolution and graph: real edges, saved to PostgreSQL
- [x] Jobs and progress: async execution, polling, caching by SHA
- [x] Atlas and code panel: compound folders, layouts, highlighting, Monaco
- [x] Search and filters
- [x] Health score and god files
- [ ] Churn (needs git history, via JGit)
- [ ] JavaScript, TypeScript and Python support
- [ ] Hardening: zip-bomb tests, error handling, timing benchmarks
- [ ] Zip upload and a visual redesign
