package com.saptarishi.backend.parse;

import com.saptarishi.backend.parse.ParsedFile.RawImport;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;
import org.treesitter.TSLanguage;
import org.treesitter.TSNode;
import org.treesitter.TreeSitterHtml;

/**
 * HTML, read with the tree-sitter grammar, for the files a page loads.
 *
 * <p>An import is the address a page loads a resource from, as written: {@code <script src>},
 * {@code <link href>} (a stylesheet, a module preload, a manifest...) and {@code <iframe src>}. A link to
 * another page ({@code <a href>}) is navigation, not a dependency, so it is not an import: every page of a
 * site links to the others, which would draw a cycle between all of them.
 *
 * <p>Only addresses that can name a file in the repository are kept: not {@code https://...},
 * {@code //cdn...}, {@code mailto:}, {@code data:}, a bare {@code #anchor}, or a template placeholder such
 * as {@code {{ url }}} or {@code <%= path %>}. Turning an address into a file is {@code HtmlImportResolver}'s
 * job.
 *
 * <p>HTML has no functions or branches, so complexity is 0. Tag and attribute names are not case sensitive.
 * A page with a mistake, or a template that is not quite HTML, is still read as far as the grammar can.
 */
@Component
public class HtmlLanguageSupport extends TreeSitterSupport {

    /** Tag name to the attribute that holds the address it loads. */
    private static final Map<String, String> LOADS = Map.of("script", "src", "link", "href", "iframe", "src");

    private static final Pattern HAS_SCHEME = Pattern.compile("^[A-Za-z][A-Za-z0-9+.-]*:");
    private static final Set<String> TEMPLATE_MARKERS = Set.of("{{", "{%", "<%", "<?", "${");

    @Override
    public String language() { return "html"; }

    @Override
    protected TSLanguage grammar() { return new TreeSitterHtml(); }

    @Override
    protected Set<String> functionTypes() { return Set.of(); }

    @Override
    protected Set<String> decisionTypes() { return Set.of(); }

    @Override
    protected void visit(TSNode node, String type, Source src, Collected out) {
        if (!type.equals("start_tag") && !type.equals("self_closing_tag")) return;
        TSNode tag = child(node, "tag_name");
        if (tag == null) return;
        String attribute = LOADS.get(src.text(tag).toLowerCase());
        if (attribute == null) return;

        for (int i = 0; i < node.getChildCount(); i++) {
            TSNode a = node.getChild(i);
            if (!a.getType().equals("attribute")) continue;
            TSNode name = child(a, "attribute_name");
            if (name == null || !src.text(name).equalsIgnoreCase(attribute)) continue;
            TSNode quoted = child(a, "quoted_attribute_value");
            TSNode value = quoted != null ? child(quoted, "attribute_value") : child(a, "attribute_value");
            if (value == null) continue;
            String address = src.text(value).trim();
            if (canNameAFile(address)) add(out, address);
        }
    }

    private static boolean canNameAFile(String address) {
        if (address.isEmpty() || address.startsWith("#") || address.startsWith("//")) return false;
        if (HAS_SCHEME.matcher(address).find()) return false;
        for (String marker : TEMPLATE_MARKERS) {
            if (address.contains(marker)) return false;
        }
        return true;
    }

    private static void add(Collected out, String address) {
        if (out.imports.stream().noneMatch(i -> i.name().equals(address))) {
            out.imports.add(new RawImport(address, false, false));
        }
    }
}