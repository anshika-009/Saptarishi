package com.saptarishi.backend.parse;

/**
 * Blanks out comments and string contents so that simple pattern matching on what is left only sees
 * code. The output has exactly the same length and the same line breaks as the input, so a position
 * in the result is a position in the original, and line numbers survive.
 *
 * <p>This is a lexer, not a parser: it knows where comments, strings, template literals and regular
 * expression literals begin and end, and nothing about the language beyond that. Where JavaScript is
 * ambiguous (a "/" can divide or start a regular expression) it guesses from the previous token, and a
 * wrong guess only affects the rest of that one line, because strings and regular expressions never
 * run past the end of a line (template literals and block comments do).
 */
final class CodeScrubber {

    private CodeScrubber() {}

    private static final String REGEX_CAN_FOLLOW = "(,=:[!&|?{;+-*%<>~^";
    private static final java.util.Set<String> REGEX_AFTER_WORD = java.util.Set.of(
            "return", "typeof", "case", "in", "of", "delete", "void", "throw", "else", "do", "yield", "await");

    /**
     * JavaScript and TypeScript. With keepStrings the contents of quoted strings stay (imports need
     * them); comments and regular expressions are blanked either way.
     */
    static String javaScript(String src, boolean keepStrings) {
        char[] out = src.toCharArray();
        int n = out.length;
        char prev = '\n';          // previous significant (non-space, non-comment) character
        StringBuilder word = new StringBuilder();
        String prevWord = "";
        int i = 0;
        while (i < n) {
            char c = src.charAt(i);
            if (Character.isJavaIdentifierPart(c)) {
                word.append(c);
                prev = c;
                i++;
                continue;
            }
            if (word.length() > 0) {
                prevWord = word.toString();
                word.setLength(0);
            }
            if (c == '/' && i + 1 < n && src.charAt(i + 1) == '/') {
                while (i < n && src.charAt(i) != '\n') out[i++] = ' ';
            } else if (c == '/' && i + 1 < n && src.charAt(i + 1) == '*') {
                int end = src.indexOf("*/", i + 2);
                int stop = end < 0 ? n : end + 2;
                for (; i < stop; i++) if (src.charAt(i) != '\n') out[i] = ' ';
            } else if (c == '\'' || c == '"') {
                i = quoted(src, out, i, c, !keepStrings);
                prev = c;
                prevWord = "";
            } else if (c == '`') {
                i = template(src, out, i, !keepStrings);
                prev = c;
                prevWord = "";
            } else if (c == '/' && startsRegex(prev, prevWord)) {
                int end = regexEnd(src, i);
                if (end < 0) {
                    prev = c;
                    i++;
                } else {
                    for (int k = i + 1; k < end; k++) out[k] = ' ';
                    prev = '/';
                    prevWord = "";
                    i = end + 1;
                }
            } else {
                if (!Character.isWhitespace(c)) {
                    prev = c;
                    prevWord = "";
                }
                i++;
            }
        }
        return new String(out);
    }

    private static boolean startsRegex(char prev, String prevWord) {
        if (Character.isJavaIdentifierPart(prev)) return REGEX_AFTER_WORD.contains(prevWord);
        return prev == '\n' || REGEX_CAN_FOLLOW.indexOf(prev) >= 0;
    }

    /** Index of the "/" closing a regular expression that starts at start, or -1 if it is not one. */
    private static int regexEnd(String src, int start) {
        boolean inClass = false;
        for (int i = start + 1; i < src.length(); i++) {
            char c = src.charAt(i);
            if (c == '\n') return -1;
            if (c == '\\') { i++; continue; }
            if (c == '[') inClass = true;
            else if (c == ']') inClass = false;
            else if (c == '/' && !inClass) return i == start + 1 ? -1 : i;
        }
        return -1;
    }

    /** Scans a quoted string; returns the index after it. A string stops at the end of its line. */
    private static int quoted(String src, char[] out, int start, char quote, boolean blank) {
        int n = src.length();
        int i = start + 1;
        while (i < n) {
            char c = src.charAt(i);
            if (c == '\n') return i;
            if (c == quote) return i + 1;
            if (c == '\\' && i + 1 < n) {
                if (blank) out[i] = ' ';
                i++;
                c = src.charAt(i);
                if (c == '\n') return i;
            }
            if (blank) out[i] = ' ';
            i++;
        }
        return n;
    }

    /** Scans a template literal, including nested ${ } expressions; returns the index after it. */
    private static int template(String src, char[] out, int start, boolean blank) {
        int n = src.length();
        int i = start + 1;
        int depth = 0; // > 0 while inside ${ ... }
        while (i < n) {
            char c = src.charAt(i);
            if (c == '\\' && i + 1 < n) {
                if (blank) { out[i] = ' '; if (src.charAt(i + 1) != '\n') out[i + 1] = ' '; }
                i += 2;
                continue;
            }
            if (c == '`') {
                if (depth == 0) return i + 1;
                int end = template(src, out, i, blank); // a template nested in an expression
                i = end;
                continue;
            }
            if (c == '$' && i + 1 < n && src.charAt(i + 1) == '{') {
                depth++;
                if (blank) { out[i] = ' '; out[i + 1] = ' '; }
                i += 2;
                continue;
            }
            if (depth > 0 && c == '{') depth++;
            else if (depth > 0 && c == '}') depth--;
            if (blank && c != '\n') out[i] = ' ';
            i++;
        }
        return n;
    }

    /** Python: "#" comments and all string forms, including triple-quoted docstrings. */
    static String python(String src) {
        char[] out = src.toCharArray();
        int n = out.length;
        int i = 0;
        while (i < n) {
            char c = src.charAt(i);
            if (c == '#') {
                while (i < n && src.charAt(i) != '\n') out[i++] = ' ';
            } else if (c == '\'' || c == '"') {
                boolean triple = i + 2 < n && src.charAt(i + 1) == c && src.charAt(i + 2) == c;
                int end;
                if (triple) {
                    String closer = "" + c + c + c;
                    end = i + 3;
                    while (end < n && !src.startsWith(closer, end)) {
                        if (src.charAt(end) == '\\') end++;
                        end++;
                    }
                    end = Math.min(n, end + 3);
                    for (int k = i + 1; k < end - 1; k++) if (src.charAt(k) != '\n') out[k] = ' ';
                    i = end;
                } else {
                    i = quotedPython(src, out, i, c);
                }
            } else {
                i++;
            }
        }
        return new String(out);
    }

    private static int quotedPython(String src, char[] out, int start, char quote) {
        int n = src.length();
        int i = start + 1;
        while (i < n) {
            char c = src.charAt(i);
            if (c == quote) return i + 1;
            if (c == '\n') return i;
            if (c == '\\' && i + 1 < n) {
                out[i] = ' ';
                i++;
                if (src.charAt(i) == '\n') return i + 1; // line continuation inside the string
            }
            out[i] = ' ';
            i++;
        }
        return n;
    }
}
