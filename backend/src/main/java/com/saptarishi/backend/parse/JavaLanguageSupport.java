package com.saptarishi.backend.parse;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ParserConfiguration.LanguageLevel;
import com.github.javaparser.Token;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.CallableDeclaration;
import com.github.javaparser.ast.body.ConstructorDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.expr.BinaryExpr;
import com.github.javaparser.ast.expr.ConditionalExpr;
import com.github.javaparser.ast.nodeTypes.NodeWithSimpleName;
import com.github.javaparser.ast.stmt.CatchClause;
import com.github.javaparser.ast.stmt.DoStmt;
import com.github.javaparser.ast.stmt.ForEachStmt;
import com.github.javaparser.ast.stmt.ForStmt;
import com.github.javaparser.ast.stmt.IfStmt;
import com.github.javaparser.ast.stmt.SwitchEntry;
import com.github.javaparser.ast.stmt.WhileStmt;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class JavaLanguageSupport implements LanguageSupport {

    private final JavaParser parser;

    public JavaLanguageSupport() {
        ParserConfiguration cfg = new ParserConfiguration();
        cfg.setLanguageLevel(LanguageLevel.JAVA_21);
        cfg.setAttributeComments(false);
        cfg.setLexicalPreservationEnabled(false);
        this.parser = new JavaParser(cfg);
    }

    @Override
    public String language() { return "java"; }

    @Override
    public ParsedFile parse(String path, String content) {
        int loc = (int) content.lines().filter(l -> !l.isBlank()).count();
        try {
            ParseResult<CompilationUnit> result = parser.parse(content);
            if (!result.isSuccessful() || result.getResult().isEmpty()) {
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
                    countTokens(cu), complexity(cu), loc, true);
        } catch (Exception | StackOverflowError e) {
            // One broken file must never fail the whole job.
            return ParsedFile.unparsed(path, language(), loc);
        }
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