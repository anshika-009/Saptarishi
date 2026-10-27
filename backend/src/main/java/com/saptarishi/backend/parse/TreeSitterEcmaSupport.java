// package com.saptarishi.backend.parse;
// import java.util.Set;

// abstract class TreeSitterEcmaSupport extends TreeSitterSupport {
//     // private static final Set<String> FUNCTIONS = Set.of(
//             "function_declaration", "generator_function_declaration", "method_definition");
//     private static final Set<String> DECISIONS = Set.of(
//             "if_statement", "for_statement", "for_in_statement", "while_statement", "do_statement",
//             "switch_case", "catch_clause", "ternary_expression", "&&", "||", "??");
//     private static final Set<String> FN_VALUES = Set.of("arrow_function", "function_expression", "function");
//     private static final Set<String> NAMES = Set.of(
//             "identifier", "type_identifier", "property_identifier", "private_property_identifier");

//     @Override protected Set<String> functionTypes() { return FUNCTIONS; }
//     @Override protected Set<String> decisionTypes() { return DECISIONS; }

//     @Override protected boolean isFunction(TSNode n, String type, Source src) {
//         return FUNCTIONS.contains(type) || ("variable_declarator".equals(type) && hasFnValue(n));
//     }

//     @Override protected void visit(TSNode n, String type, Source src, Collected out) {
//         switch (type) {
//             case "import_statement", "export_statement" -> addImport(child(n, "string"), src, out);
//             case "call_expression" -> {
//                 TSNode callee = n.getChild(0), args = child(n, "arguments");
//                 if (callee == null || args == null) return;
//                 boolean req = "identifier".equals(callee.getType()) && "require".equals(src.text(callee));
//                 if (req || "import".equals(callee.getType())) addImport(child(args, "string"), src, out);
//             }
//             case "class_declaration", "abstract_class_declaration" -> name(n, "CLASS", src, out);
//             case "interface_declaration", "type_alias_declaration" -> name(n, "INTERFACE", src, out);
//             case "enum_declaration" -> name(n, "ENUM", src, out);
//             case "function_declaration", "generator_function_declaration" -> name(n, "METHOD", src, out);
//             case "method_definition" -> name(n, "METHOD", src, out);       // see note below
//             case "variable_declarator" -> { if (hasFnValue(n)) name(n, "METHOD", src, out); }
//             default -> { }
//         }
//     }
//     // helpers: addImport (strip quotes, skip null, dedupe), name (first child whose type is in NAMES,
//     // add Symbol(text, kind, src.line(nameNode)); use CONSTRUCTOR when the name text is "constructor"),
//     // hasFnValue (any direct child type in FN_VALUES)
// }