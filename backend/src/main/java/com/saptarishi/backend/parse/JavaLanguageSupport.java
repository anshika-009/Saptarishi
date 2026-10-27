package com.saptarishi.backend.parse;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ParserConfiguration.LanguageLevel;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.CallableDeclaration;
import com.github.javaparser.ast.body.ConstructorDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.body.RecordDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.BinaryExpr;
import com.github.javaparser.ast.expr.CastExpr;
import com.github.javaparser.ast.expr.ConditionalExpr;
import com.github.javaparser.ast.expr.EnclosedExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.expr.SuperExpr;
import com.github.javaparser.ast.expr.ThisExpr;
import com.github.javaparser.ast.stmt.CatchClause;
import com.github.javaparser.ast.stmt.DoStmt;
import com.github.javaparser.ast.stmt.ForEachStmt;
import com.github.javaparser.ast.stmt.ForStmt;
import com.github.javaparser.ast.stmt.IfStmt;
import com.github.javaparser.ast.stmt.SwitchEntry;
import com.github.javaparser.ast.stmt.WhileStmt;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import com.github.javaparser.ast.type.Type;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class JavaLanguageSupport implements LanguageSupport {

    private static final Logger log = LoggerFactory.getLogger(JavaLanguageSupport.class);

    /**
     * A JavaParser reuses its internal state between calls, so one instance must not be used by two
     * threads at once, and RepoParser parses files on a pool of threads. Each thread gets its own.
     */
    private final ThreadLocal<JavaParser> parser = ThreadLocal.withInitial(() -> {
        ParserConfiguration cfg = new ParserConfiguration();
        cfg.setLanguageLevel(LanguageLevel.JAVA_21);
        cfg.setAttributeComments(false);
        cfg.setLexicalPreservationEnabled(false);
        return new JavaParser(cfg);
    });

    @Override
    public String language() { return "java"; }

    @Override
    public ParsedFile parse(String path, String content) {
        int loc = (int) content.lines().filter(l -> !l.isBlank()).count();
        try {
            ParseResult<CompilationUnit> result = parser.get().parse(content);
            if (!result.isSuccessful() || result.getResult().isEmpty()) {
                // Say why, so "could not be parsed" can be traced to a cause.
                log.warn("Java parse failed for {}: {}", path,
                        result.getProblems().isEmpty() ? "no result" : result.getProblems().get(0).getMessage());
                return ParsedFile.unparsed(path, language(), loc);
            }
            CompilationUnit cu = result.getResult().get();

            String pkg = cu.getPackageDeclaration().map(p -> p.getNameAsString()).orElse("");

            List<ParsedFile.RawImport> imports = cu.getImports().stream()
                    .map(i -> new ParsedFile.RawImport(i.getNameAsString(), i.isStatic(), i.isAsterisk()))
                    .toList();

            List<ParsedFile.Symbol> symbols = new ArrayList<>();
            for (TypeDeclaration<?> t : cu.findAll(TypeDeclaration.class)) {
                symbols.add(new ParsedFile.Symbol(t.getNameAsString(), typeKind(t), line(t)));
            }
            for (MethodDeclaration m : cu.findAll(MethodDeclaration.class)) {
                symbols.add(new ParsedFile.Symbol(m.getNameAsString(), "METHOD", line(m)));
            }
            for (ConstructorDeclaration c : cu.findAll(ConstructorDeclaration.class)) {
                symbols.add(new ParsedFile.Symbol(c.getNameAsString(), "CONSTRUCTOR", line(c)));
            }
            symbols.sort((a, b) -> Integer.compare(a.line(), b.line()));

            return new ParsedFile(path, language(), pkg, imports, symbols,
                    countTokens(cu), complexity(cu), loc, true, typeRefs(cu),
                    methods(cu), calls(cu));
        } catch (Exception | StackOverflowError e) {
            // One broken file must never fail the whole job.
            log.warn("Java parse failed for {}: {}", path, e.toString());
            return ParsedFile.unparsed(path, language(), loc);
        }
    }


    // ---- call graph facts (resolved later, across files, by CallGraphBuilder) ----

    private record Scope(Map<String, String> locals, Map<String, String> fields) {}

    private static List<ParsedFile.MethodDecl> methods(CompilationUnit cu) {
        List<ParsedFile.MethodDecl> out = new ArrayList<>();
        for (CallableDeclaration<?> c : cu.findAll(CallableDeclaration.class)) {
            out.add(new ParsedFile.MethodDecl(enclosingType(c), c.getNameAsString(),
                    c.getParameters().size(), line(c), 1 + decisionPoints(c)));
        }
        return out;
    }

    private static List<ParsedFile.CallSite> calls(CompilationUnit cu) {
        List<ParsedFile.CallSite> out = new ArrayList<>();
        Map<Node, Scope> scopes = new IdentityHashMap<>();

        for (MethodCallExpr call : cu.findAll(MethodCallExpr.class)) {
            CallableDeclaration<?> caller = enclosingCallable(call);
            if (caller == null) continue; // field initialisers, static blocks
            Scope scope = scopes.computeIfAbsent(caller, k -> scopeOf(caller));
            Expression target = call.getScope().orElse(null);
            String receiver = target == null ? "" : typeOf(target, scope);
            if ("super".equals(receiver)) continue;
            out.add(new ParsedFile.CallSite(enclosingType(caller), caller.getNameAsString(),
                    caller.getParameters().size(), call.getNameAsString(),
                    call.getArguments().size(), receiver));
        }
        for (ObjectCreationExpr created : cu.findAll(ObjectCreationExpr.class)) {
            CallableDeclaration<?> caller = enclosingCallable(created);
            if (caller == null) continue;
            String type = created.getType().getNameAsString();
            out.add(new ParsedFile.CallSite(enclosingType(caller), caller.getNameAsString(),
                    caller.getParameters().size(), type, created.getArguments().size(), type));
        }
        return out;
    }

    /** Simple type name of the expression a method is called on, or null when it can't be told cheaply. */
    private static String typeOf(Expression e, Scope s) {
        if (e instanceof ThisExpr) return "";
        if (e instanceof SuperExpr) return "super";
        if (e instanceof EnclosedExpr en) return typeOf(en.getInner(), s);
        if (e instanceof CastExpr c) {
            return c.getType().isClassOrInterfaceType()
                    ? c.getType().asClassOrInterfaceType().getNameAsString() : null;
        }
        if (e instanceof ObjectCreationExpr o) return o.getType().getNameAsString();
        if (e instanceof NameExpr n) {
            String id = n.getNameAsString();
            String t = s.locals().get(id);
            if (t == null) t = s.fields().get(id);
            if (t != null) return t;
            return Character.isUpperCase(id.charAt(0)) ? id : null; // Util.trim(): static call
        }
        if (e instanceof FieldAccessExpr f) {
            String id = f.getNameAsString();
            if (f.getScope() instanceof ThisExpr) return s.fields().get(id);
            return Character.isUpperCase(id.charAt(0)) ? id : null; // pkg.Util.trim()
        }
        return null; // chained call, array access, ...
    }

    private static Scope scopeOf(CallableDeclaration<?> c) {
        Map<String, String> locals = new HashMap<>();
        for (Parameter p : c.getParameters()) putType(locals, p.getNameAsString(), p.getType());
        for (VariableDeclarator v : c.findAll(VariableDeclarator.class)) {
            if (v.getType().isVarType()) {
                v.getInitializer().filter(i -> i instanceof ObjectCreationExpr).ifPresent(i ->
                        locals.putIfAbsent(v.getNameAsString(),
                                ((ObjectCreationExpr) i).getType().getNameAsString()));
            } else {
                putType(locals, v.getNameAsString(), v.getType());
            }
        }
        Map<String, String> fields = new HashMap<>();
        for (Node n = c.getParentNode().orElse(null); n != null; n = n.getParentNode().orElse(null)) {
            if (!(n instanceof TypeDeclaration<?> t)) continue;
            for (FieldDeclaration f : t.getFields()) {
                for (VariableDeclarator v : f.getVariables()) putType(fields, v.getNameAsString(), v.getType());
            }
            if (t instanceof RecordDeclaration r) {
                for (Parameter rp : r.getParameters()) putType(fields, rp.getNameAsString(), rp.getType());
            }
        }
        return new Scope(locals, fields);
    }

    private static void putType(Map<String, String> m, String name, Type t) {
        if (t.isClassOrInterfaceType()) m.putIfAbsent(name, t.asClassOrInterfaceType().getNameAsString());
    }

    private static CallableDeclaration<?> enclosingCallable(Node n) {
        for (Node p = n.getParentNode().orElse(null); p != null; p = p.getParentNode().orElse(null)) {
            if (p instanceof CallableDeclaration<?> c) return c;
        }
        return null;
    }

    private static String enclosingType(Node n) {
        for (Node p = n.getParentNode().orElse(null); p != null; p = p.getParentNode().orElse(null)) {
            if (p instanceof TypeDeclaration<?> t) return t.getNameAsString();
        }
        return "";
    }

    private static String typeKind(TypeDeclaration<?> t) {
        if (t.isEnumDeclaration()) return "ENUM";
        if (t.isRecordDeclaration()) return "RECORD";
        if (t.isClassOrInterfaceDeclaration()) {
            return t.asClassOrInterfaceDeclaration().isInterface() ? "INTERFACE" : "CLASS";
        }
        return "CLASS"; // annotation declarations etc.
    }

    private static int line(Node n) {
        return n.getBegin().map(p -> p.line).orElse(0);
    }

    /**
     * Simple names of types this file uses: declared types (fields, params, extends...),
     * annotations, and capitalised names in expressions (Owner.create(), Color.RED).
     * Over-collecting is harmless: a name only becomes an edge if a class with that name
     * exists in the same package.
     */
    private static List<String> typeRefs(CompilationUnit cu) {
        Set<String> names = new TreeSet<>();
        cu.findAll(ClassOrInterfaceType.class).forEach(t -> names.add(t.getNameAsString()));
        cu.findAll(AnnotationExpr.class).forEach(a -> names.add(a.getName().getIdentifier()));
        cu.findAll(NameExpr.class).forEach(n -> names.add(n.getNameAsString()));
        names.removeIf(n -> n.isEmpty() || !Character.isUpperCase(n.charAt(0)));
        return List.copyOf(names);
    }

    /**
     * Token count: the number of lexical tokens in the file, excluding
     * whitespace and comments. We take the leaf tokens from the parsed tree's token range.
     */
    private static int countTokens(CompilationUnit cu) {
        var range = cu.getTokenRange();
        if (range.isEmpty()) return 0;
        int count = 0;
        for (var tok : range.get()) {
            var kind = tok.getCategory();
            if (kind.isWhitespaceOrComment() || kind.isEndOfLine()) continue;
            count++;
        }
        return count;
    }

    /**
     * Cyclomatic complexity: for each method/constructor, 1 + decision points
     * (if, for, foreach, while, do, each non-default case, catch, ?:, && and ||).
     * The file's complexity is the sum over its methods and constructors.
     * A file with no methods (e.g. an empty interface) gets 0.
     */
    private static int complexity(CompilationUnit cu) {
        int total = 0;
        for (CallableDeclaration<?> c : cu.findAll(CallableDeclaration.class)) {
            total += 1 + decisionPoints(c);
        }
        return total;
    }

    private static int decisionPoints(Node body) {
        int n = 0;
        n += body.findAll(IfStmt.class).size();
        n += body.findAll(ForStmt.class).size();
        n += body.findAll(ForEachStmt.class).size();
        n += body.findAll(WhileStmt.class).size();
        n += body.findAll(DoStmt.class).size();
        n += body.findAll(CatchClause.class).size();
        n += body.findAll(ConditionalExpr.class).size();
        n += (int) body.findAll(SwitchEntry.class).stream()
                .filter(e -> !e.getLabels().isEmpty()).count(); // default has no labels
        n += (int) body.findAll(BinaryExpr.class).stream()
                .filter(b -> b.getOperator() == BinaryExpr.Operator.AND
                          || b.getOperator() == BinaryExpr.Operator.OR).count();
        return n;
    }
}