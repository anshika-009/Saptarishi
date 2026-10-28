package com.saptarishi.backend.graph;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;

/** Test and generated files are recognised by each supported language's conventions, so none of them is a starter. */
class TestPathsTest {

    @Test void testFilesOfEveryLanguageAreRecognised() {
        for (String p : List.of(
                "src/test/java/org/app/OwnerTest.java", "src/main/java/org/app/OwnerIT.java", "app/UserTests.java",
                "app/Models/UserTest.php", "tests/Unit/CartTest.php",
                "spec/models/cart_spec.rb", "lib/cart_test.rb", "test/test_cart.rb",
                "src/parser_test.cc", "src/ParserTest.cpp", "src/test_parser.c", "src/parser_unittest.cc",
                "pkg/handler_test.go", "src/lexer_test.rs", "src/UserTests.cs",
                "web/a.test.tsx", "web/__tests__/a.js", "pkg/test_models.py", "pkg/models_test.py")) {
            assertTrue(HealthScore.isTestPath(p), p);
        }
    }

    @Test void ordinaryFilesWithTestishNamesAreNot() {
        for (String p : List.of(
                "src/latest.ts", "pkg/contest.py", "src/Latest.java", "src/main/java/org/app/Protest.java",
                "lib/respect.rb", "src/inspector.cc", "src/main/java/org/app/Owner.java", "app/models/cart.rb",
                "src/parser.cpp", "src/Contest.cs")) {
            assertFalse(HealthScore.isTestPath(p), p);
        }
    }

    @Test void generatedFilesAreBoilerplate() {
        for (String p : List.of("api/user.pb.go", "api/types_generated.go", "api/zz_generated.deepcopy.go",
                "gen/user_pb2.py", "gen/user_pb2_grpc.py", "gen/user.pb.cc", "gen/user.pb.h", "types/index.d.ts",
                "src/Foo.Designer.cs", "src/package-info.java")) {
            assertTrue(HealthScore.isBoilerplate(p), p);
        }
        assertFalse(HealthScore.isBoilerplate("api/user.go"));
        assertFalse(HealthScore.isBoilerplate("gen/user.py"));
    }
}
