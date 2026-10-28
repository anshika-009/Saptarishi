package com.saptarishi.backend.calls;

import static com.saptarishi.backend.calls.CallGraphAssert.*;
import static org.junit.jupiter.api.Assertions.*;

import com.saptarishi.backend.calls.CallGraph.CallEdge;
import com.saptarishi.backend.parse.CSharpLanguageSupport;
import java.util.List;
import org.junit.jupiter.api.Test;

class CSharpCallGraphTest {

    private static final CSharpLanguageSupport CS = new CSharpLanguageSupport();

    static final String SERVICE = "App/Service.cs";
    static final String REPO = "App/Data/Repo.cs";
    static final String STORE = "App/Data/IStore.cs";
    static final String UTIL = "App/Util.cs";
    static final String PART = "App/Service.Part.cs";

    private final CallGraph graph = build(List.of(
            CS.parse(SERVICE, """
                    using App.Data;
                    namespace App {
                        public partial class Service {
                            private readonly Repo _repo;
                            private readonly IStore _store;
                            public void Run(int id) {
                                _repo.Find(id);
                                Helper();
                                Extra();
                                Util.Clean("x");
                                _store.Save(id);
                                new Repo(1);
                                make().Unique(2);
                                text.Trim();
                            }
                            void Helper() {}
                        }
                    }
                    """),
            CS.parse(PART, "namespace App { public partial class Service { void Extra() {} } }\n"),
            CS.parse(REPO, "namespace App.Data { public class Repo { public Repo(int u) {} public void Find(int id) { Audit(id); } void Audit(int id) {} } }\n"),
            CS.parse(STORE, "namespace App.Data { public interface IStore { void Save(int id); } }\n"),
            CS.parse(UTIL, "namespace App { public static class Util { public static string Clean(string s) { return s; } public static void Unique(int n) {} } }\n")));

    private final String run = id(SERVICE, "Service", "Run", 1);

    @Test void resolvesFieldStaticInterfacePartialClassAndConstructorCallsExactly() {
        for (String callee : List.of(id(REPO, "Repo", "Find", 1), id(SERVICE, "Service", "Helper", 0),
                id(PART, "Service", "Extra", 0), id(UTIL, "Util", "Clean", 1), id(STORE, "IStore", "Save", 1),
                id(REPO, "Repo", "Repo", 1))) {
            CallEdge e = edge(graph, run, callee).orElseThrow(() -> new AssertionError("missing edge to " + callee));
            assertFalse(e.guess(), callee);
        }
        assertTrue(edge(graph, id(REPO, "Repo", "Find", 1), id(REPO, "Repo", "Audit", 1)).isPresent());
    }

    @Test void unknownReceiverIsAGuessAndLibrariesAreNotLinked() {
        assertTrue(edge(graph, run, id(UTIL, "Util", "Unique", 1)).orElseThrow().guess());
        assertTrue(graph.edges().stream().noneMatch(e -> e.callee().contains("Trim")));
    }
}
