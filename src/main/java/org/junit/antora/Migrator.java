package org.junit.antora;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.function.UnaryOperator;
import java.util.regex.MatchResult;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static java.nio.file.StandardCopyOption.REPLACE_EXISTING;
import static java.util.stream.Collectors.toCollection;

/**
 * Converts the {@code assertj-<module>*.adoc} documents of the old Asciidoctor guide into one
 * self-contained Antora component each.
 */
public class Migrator {

    private static final List<String> MODULES = List.of("core", "guava");

    /** The only document that is split into subpages instead of becoming a single page. */
    private static final String SPLIT_DOCUMENT = "assertj-core-assertions-guide.adoc";

    /** Sections of the split document shorter than this stay on its index page. */
    private static final int MIN_SUBPAGE_LINES = 10;

    /** Longer sections of the split document that stay on its index page nevertheless. */
    private static final List<String> INDEX_SECTIONS = List.of("[[assertj-core-simple-example]]");

    /** Sections of the split document that are appended to the subpage of an earlier section, by its file name. */
    /** Stays a plain Antora example because it never compiled: it lacks imports and fields. */
    private static final List<String> UNCOMPILED_EXAMPLES = List.of("example/core/AssumptionsDemo.java");
    /** Not included by any page but needed to compile the examples. */
    private static final Map<String, List<String>> EXAMPLE_DEPENDENCIES = Map.of("core", List.of("example/data"));

    /** Pages that start with a hand-written table of contents, which the Antora UI renders itself. */
    private static final Map<String, List<String>> PAGES_WITH_MANUAL_TOC = Map.of(
            "core", List.of("extension.adoc", "release-notes.adoc"),
            "guava", List.of("release-notes.adoc"),
            "db", List.of("concepts.adoc", "features.adoc", "release-notes.adoc")
    );

    private static final Map<String, String> SUBPAGE_SECTIONS = Map.of(
            "[[assertj-core-common-assertions]]", "supported-types.adoc",
            "[[assertj-core-object-assertions]]", "supported-types.adoc",
            "[[assertj-string-object-assertions]]", "supported-types.adoc"
    );

    /** Links that were already broken in the old guide, mapped to the anchor they meant. */
    private static final Map<String, String> ANCHOR_FIXES = Map.of(
            "assertj-core-junit5-soft-assertions-field-parameter", "assertj-core-junit5-soft-assertions-parameter-injection",
            "assertj-core-junit5-softly-extension", "assertj-core-junit5-soft-assertions",
            "assertj-core-junit5-soft-assertions-custom-injection", "assertj-core-junit5-parameter-soft-assertions-custom-injection",
            "assertj-core-custom-representation-default-global-scope", "assertj-core-custom-representation-global-scope-change"
    );

    static void main(String[] args) throws Exception {
        if (args.length != 3) {
            throw new IllegalArgumentException("Usage: <doc repository dir> <assertj docs dir> <assertj-db docs dir>, but was:\n" + Arrays.toString(args));
        }
        var docDir = Path.of(args[0]).toAbsolutePath().normalize();
        var targetDir = Path.of(args[1]).toAbsolutePath().normalize();
        for (var module : MODULES) {
            new Migrator(docDir, targetDir, targetDir.resolve("assertj-" + module), module).run();
        }
        writeAttributesTemplate(docDir, targetDir, "assertj-version");
        // the only component of its repository, so the docs dir itself is the component
        var dbTargetDir = Path.of(args[2]).toAbsolutePath().normalize();
        new Migrator(docDir, dbTargetDir, dbTargetDir, "db").run();
        writeAttributesTemplate(docDir, dbTargetDir, "assertj-db-version");
    }

    private final String module;
    private final Path userGuideDir;
    private final Path testJavaDir;
    private final Path targetDir;
    private final Path componentDir;
    private final Path moduleDir;
    private final Path pagesDir;

    private boolean hasExamples;
    private final List<Document> documents = new ArrayList<>();
    private final List<String> nav = new ArrayList<>();

    /** Explicit anchors of this module only, so a link that leaves the module fails as unresolved. */
    private final Map<String, AnchorTarget> anchors = new HashMap<>();
    /** Auto-generated section ids, for links that rely on them or reference a section by title. */
    private final Map<String, AnchorTarget> autoAnchors = new HashMap<>();

    private Migrator(Path docDir, Path targetDir, Path componentDir, String module) {
        this.module = module;
        this.targetDir = targetDir;
        this.componentDir = componentDir;
        userGuideDir = docDir.resolve("src/docs/asciidoc/user-guide");
        testJavaDir = docDir.resolve("src/test/java");
        moduleDir = componentDir.resolve("modules/ROOT");
        pagesDir = moduleDir.resolve("pages");
    }

    private void run() throws IOException {
        // the wrapper document becomes the module's start page, the documents it includes become pages
        var index = load(userGuideDir.resolve("assertj-%s.adoc".formatted(module)), pagesDir.resolve("index.adoc"));
        var includes = Pattern.compile("(?m)^include::(assertj-%s-(.+)\\.adoc)\\[]\n*".formatted(module)).matcher(index.text);
        var included = includes.results().toList();
        index.text = includes.replaceAll("").strip();
        var title = index.text.lines().filter(line -> line.startsWith("=")).findFirst().orElseThrow().replaceFirst("^=+ ", "");
        // a wrapper that is only an anchor and a title would be an empty start page, its first document takes that place
        var emptyWrapper = index.text.lines().count() <= 2;
        if (!emptyWrapper) {
            migrate(index, 1, 1);
            // the component title already heads the navigation, repeating it as an entry would nest everything under it
            nav.set(0, "* xref:index.adoc[Overview]");
        }

        for (var include : included) {
            var source = userGuideDir.resolve(include.group(1));
            if (SPLIT_DOCUMENT.equals(include.group(1))) {
                split(source, pagesDir.resolve(include.group(2)));
            } else {
                var name = emptyWrapper && include == included.getFirst() ? "index" : include.group(2);
                migrate(load(source, pagesDir.resolve(name + ".adoc")), 2, 1);
            }
        }
        checkAllDocumentsAreIncluded(included);
        migrateDependencyMetadata();

        documents.forEach(Document::collectAnchors);
        documents.forEach(Document::collectAutoAnchors);
        documents.forEach(Document::replaceLinks);

        for (var doc : documents) {
            doc.save();
        }
        Files.write(moduleDir.resolve("nav.adoc"), nav);
        copyExamples();
        writeAntoraYml(title);
        copyImages();
        System.out.printf("Module '%s': %d pages%n", module, documents.size());
    }

    private void checkAllDocumentsAreIncluded(List<MatchResult> included) throws IOException {
        try (var files = Files.newDirectoryStream(userGuideDir, "assertj-%s-*.adoc".formatted(module))) {
            for (var file : files) {
                if (included.stream().noneMatch(include -> include.group(1).equals(file.getFileName().toString()))) {
                    throw new IllegalStateException("Not included by the %s wrapper document: %s".formatted(module, file));
                }
            }
        }
    }

    /** One page per level 4 section, named after its anchor; short sections stay on the index page. */
    private void split(Path source, Path targetDir) throws IOException {
        var parts = Files.readString(source).splitWithDelimiters("\\[\\[.+]]\n==== ", -1);
        var index = new Document(targetDir.resolve("index.adoc"), parts[0]);
        List<Document> subpages = new ArrayList<>();
        var pattern = Pattern.compile("^\\[\\[(?:assertj-)?(?:core-)?(.+)]]\n");
        for (var i = 1; i < parts.length; i++) {
            var text = parts[i] + parts[++i];
            var subpageName = SUBPAGE_SECTIONS.get(text.lines().findFirst().orElseThrow());
            if (subpageName != null) {
                var subpage = subpages.stream().filter(doc -> doc.file.endsWith(subpageName)).findFirst().orElseThrow();
                // one level down, it is a section of that page now
                subpage.text += text.replaceFirst("\n==== ", "\n===== ");
                continue;
            }
            if (text.lines().count() < MIN_SUBPAGE_LINES || INDEX_SECTIONS.stream().anyMatch(text::startsWith)) {
                index.text += text;
                continue;
            }
            var matcher = pattern.matcher(text);
            if (!matcher.find()) {
                throw new IllegalArgumentException("Text did not start as expected");
            }
            subpages.add(new Document(targetDir.resolve(matcher.group(1) + ".adoc"), text));
        }
        migrate(index, 2, 1);
        subpages.forEach(doc -> migrate(doc, 3, 2));
    }

    /** Copies this module's and the BOM's sections of the appendix, so quickstart links stay in the module. */
    private void migrateDependencyMetadata() throws IOException {
        var appendix = Files.readString(userGuideDir.resolve("assertj-appendix.adoc"));
        if (!appendix.contains("[[dependency-metadata-assertj-%s]]".formatted(module))) {
            return;
        }
        var parts = appendix.split("(?=\\[\\[dependency-metadata-)");
        var text = new StringBuilder(parts[0].replaceFirst("^== .+\n+", ""));
        for (var i = 1; i < parts.length; i++) {
            if (parts[i].startsWith("[[dependency-metadata-assertj-%s]]".formatted(module))
                    || parts[i].startsWith("[[dependency-metadata-assertj-bom]]")) {
                text.append(parts[i]);
            }
        }
        migrate(new Document(pagesDir.resolve("dependency-metadata.adoc"), text.toString()), 2, 1);
    }

    private void migrate(Document doc, int headingLevels, int navLevel) {
        doc.text = doc.text.strip() + "\n";
        doc.reduceHeadingLevel(headingLevels);
        doc.replaceTestDirs();
        doc.removeTopLevelAnchor();
        if (PAGES_WITH_MANUAL_TOC.getOrDefault(module, List.of()).contains(doc.file.getFileName().toString())) {
            doc.removeManualToc();
        }
        // mistakes in the old guide that the old build tolerated but Antora reports
        doc.text = doc.text.replaceAll("(?<=\n)= ", "== "); // a section on the level of its page title
        doc.replace("{assertj-examples}/[", "{assertj-examples-github}/["); // attribute never existed
        doc.replace("{Interface}", "\\{Interface}"); // literal text, not an attribute
        doc.replace("| URL and URI\n|===", "| URL and URI |\n|==="); // odd cell count dropped the last row
        documents.add(doc);
        nav.add("%s xref:%s[]".formatted("*".repeat(navLevel), pagesDir.relativize(doc.file)));
    }

    private Document load(Path source, Path target) throws IOException {
        return new Document(target, Files.readString(source));
    }

    private void copyImages() throws IOException {
        try (var images = Files.newDirectoryStream(userGuideDir.resolve("images"))) {
            for (var image : images) {
                if (documents.stream().anyMatch(doc -> doc.text.contains(image.getFileName() + "["))) {
                    copy(image, moduleDir.resolve("images").resolve(image.getFileName()));
                }
            }
        }
    }

    /** Examples go to the Maven test sources so they are compiled; the collector hands them to Antora from there. */
    private void copyExamples() throws IOException {
        var compiledDir = targetDir.resolve("src/test/java");
        var pattern = Pattern.compile("include::example\\$java/(.+?)\\[");
        for (var doc : documents) {
            for (var include : pattern.matcher(doc.text).results().toList()) {
                var example = include.group(1);
                var targetDir = UNCOMPILED_EXAMPLES.contains(example) ? moduleDir.resolve("examples/java") : compiledDir;
                copy(testJavaDir.resolve(example), targetDir.resolve(example));
                hasExamples = true;
            }
        }
        if (hasExamples) {
            for (var dir : EXAMPLE_DEPENDENCIES.getOrDefault(module, List.of())) {
                try (var files = Files.newDirectoryStream(testJavaDir.resolve(dir))) {
                    for (var file : files) {
                        copy(file, compiledDir.resolve(dir).resolve(file.getFileName()));
                    }
                }
            }
        }
    }

    private static void copy(Path source, Path target) throws IOException {
        Files.createDirectories(target.getParent());
        Files.copy(source, target, REPLACE_EXISTING);
    }

    /** Version and attributes are not here: the collector overlays them from the file Maven filters. */
    private void writeAntoraYml(String title) throws IOException {
        Files.writeString(componentDir.resolve("antora.yml"), """
                name: assertj-%s
                title: %s
                version: true
                nav:
                  - modules/ROOT/nav.adoc
                ext:
                  collector:
                    - run:
                        command: ./mvnw -q -f docs process-resources
                        local: true
                      scan:
                        - dir: docs/target/antora
                """.formatted(module, title)
                + (hasExamples ? "        - dir: docs/src/test/java\n          into: modules/ROOT/examples/java\n" : ""));
    }

    /** Shared by all components and filtered by Maven, so that versions come from the pom. */
    private static void writeAttributesTemplate(Path docDir, Path targetDir, String versionAttribute) throws IOException {
        Map<String, String> attributes = new LinkedHashMap<>();
        // the old build used these, links to auto-generated section ids depend on them
        attributes.put("idprefix", "");
        attributes.put("idseparator", "-");
        attributes.put("tabsize", "3");
        attributes.put("source-indent", "0");
        attributes.put(versionAttribute, "${project.version}");
        var attributePattern = Pattern.compile("^:(.+?):\\s+(.+)$");
        for (var line : Files.readAllLines(docDir.resolve("src/docs/asciidoc/link-attributes.adoc"))) {
            var matcher = attributePattern.matcher(line);
            if (matcher.matches()) {
                var value = matcher.group(2);
                // resolved here because a value in antora.yml can't refer to its sibling attributes
                for (var attribute : attributes.entrySet()) {
                    value = value.replace("{%s}".formatted(attribute.getKey()), attribute.getValue());
                }
                // what is still unresolved belongs to other modules (joda) and would fail builds that warn on missing attributes
                if (!value.matches(".*(?<!\\$)\\{[\\w-]+}.*")) {
                    attributes.put(matcher.group(1), value);
                }
            }
        }
        var yml = new StringBuilder("""
                version: ${antora-component.version}
                prerelease: ${antora-component.prerelease}
                asciidoc:
                  attributes:
                """);
        attributes.forEach((name, value) -> yml.append("    %s: '%s'\n".formatted(name, value.replace("'", "''"))));
        var template = targetDir.resolve("antora-resources/antora.yml");
        Files.createDirectories(template.getParent());
        Files.writeString(template, yml);
    }

    /** Same result as Asciidoctor's section id generation with an empty idprefix and '-' as idseparator. */
    private static String autoId(String title) {
        return title.toLowerCase(Locale.ROOT)
                .replaceAll("[^ \\w\\-.]", "")
                .replaceAll("[ .\\-]+", "-")
                .replaceAll("^-|-$", "");
    }

    private class Document {

        final Path file;
        String text;

        Document(Path file, String text) {
            this.file = file;
            this.text = text;
        }

        void removeTopLevelAnchor() {
            var pattern = Pattern.compile("^\\[\\[(.+?)]]\\s*");
            var matcher = pattern.matcher(text);
            if (matcher.find()) {
                register(matcher.group(1), new AnchorTarget.Page(file));
                text = matcher.replaceFirst("");
            }
        }

        /** The first list that starts with a link, with the line introducing it. */
        void removeManualToc() {
            var toc = Pattern.compile("(?m)(?:^(?:Latest release notes|Older release notes|Sections):\n+)?^[*-]+ (?:<<|link:#).*\n+(?:^[*-]+ .*\n+)*").matcher(text);
            if (!toc.find()) {
                throw new IllegalStateException("No manual table of contents in " + file);
            }
            text = toc.replaceFirst("");
        }

        void replaceTestDirs() {
            replace("{testDir}", "example$java");
        }

        void replace(String from, String to) {
            text = text.replace(from, to);
        }

        void reduceHeadingLevel(int levels) {
            Pattern pattern = Pattern.compile("^(=+)={%d} ".formatted(levels));
            transformLines(lines -> lines.stream()
                    .map(line -> pattern.matcher(line).replaceFirst("$1 "))
                    .toList());
        }

        void collectAnchors() {
            var newRefs = findAllAnchorsAndRemoveCommonPrefix();
            var matcher = Pattern.compile("(?m)(?:\\[\\[|^\\[#)([^],]+)]+.*\n(?:\\[(?!\\[).+?]\n)?(?:(?:\\.|=+ )(.+?)\n)?").matcher(text);
            while (matcher.find()) {
                var ref = matcher.group(1);
                var title = matcher.group(2);
                var fragment = new AnchorTarget.Fragment(file, title, newRefs.getOrDefault(ref, ref));
                register(ref, fragment);
                if (title != null) {
                    autoAnchors.putIfAbsent(autoId(title), fragment);
                }
            }
            newRefs.forEach((oldRef, newRef) -> replace("[[%s]]".formatted(oldRef), "[[%s]]".formatted(newRef)));
        }

        /** The old single-page guide needed prefixes like "assertj-core-" to keep ids unique, a page of its own does not. */
        private Map<String, String> findAllAnchorsAndRemoveCommonPrefix() {
            var projectPrefix = "^assertj-(?:%s-)?".formatted(module);
            Map<String, String> newRefs = new LinkedHashMap<>();
            Pattern.compile("\\[\\[([^],]+)]]").matcher(text).results().map(match -> match.group(1))
                    .forEach(ref -> newRefs.put(ref, ref.replaceFirst(projectPrefix, "")));
            // the common prefix of a single anchor would be the anchor itself
            if (newRefs.size() > 1) {
                var prefix = newRefs.values().iterator().next();
                for (var ref : newRefs.values()) {
                    while (!ref.startsWith(prefix)) {
                        prefix = prefix.substring(0, prefix.length() - 1);
                    }
                }
                var prefixLength = prefix.lastIndexOf('-') + 1;
                newRefs.replaceAll((oldRef, newRef) -> newRef.substring(prefixLength));
            }
            // what remains of a release notes anchor starts with the version, an id should not start with a digit
            newRefs.replaceAll((oldRef, newRef) -> Character.isDigit(newRef.charAt(0)) ? "v" + newRef : newRef);
            return newRefs;
        }

        void collectAutoAnchors() {
            var matcher = Pattern.compile("(?m)^==+ (.+)$").matcher(text);
            while (matcher.find()) {
                var id = autoId(matcher.group(1));
                autoAnchors.putIfAbsent(id, new AnchorTarget.Fragment(file, matcher.group(1), id));
            }
        }

        private void register(String ref, AnchorTarget target) {
            var previous = anchors.put(ref, target);
            if (previous != null) {
                System.out.printf("WARNING: duplicate anchor '%s' in %s and %s%n", ref, pagesDir.relativize(previous.file()), pagesDir.relativize(file));
            }
        }

        void replaceLinks() {
            // this anchor never existed and nothing resembles it, keep only the link text
            replaceAll("link:#assertj-core-3\\.16\\.0-byte-array-encodedAsBase64\\[(.+?)]", match -> match.group(1));
            // the old single-page guide mostly used link:#id[text] for cross references
            replaceAll("link:(?:index\\.html)?#([^\\[\\s]+)\\[", match -> "xref:%s[".formatted(toXrefTarget(resolve(match.group(1), match))));
            replaceAll("<<(?:[^#,>\\s]+#)?(.+?)(?:,\\s*((?:.|\n)+?))?>>", match -> {
                var title = match.group(2);
                var target = resolve(match.group(1).strip(), match);
                if (target instanceof AnchorTarget.Fragment fragment) {
                    if (fragment.file().equals(file)) {
                        return title == null ? "<<%s>>".formatted(fragment.ref()) : "<<%s,%s>>".formatted(fragment.ref(), title);
                    }
                    if (title == null) {
                        // an xref without text would render the page title instead of the section title
                        title = fragment.title() == null ? fragment.ref() : fragment.title();
                    }
                }
                return "xref:%s[%s]".formatted(toXrefTarget(target), title == null ? "" : title);
            });
        }

        private AnchorTarget resolve(String ref, MatchResult match) {
            ref = ANCHOR_FIXES.getOrDefault(ref, ref);
            var target = anchors.get(ref);
            if (target == null) {
                target = autoAnchors.getOrDefault(ref, autoAnchors.get(autoId(ref)));
            }
            if (target == null) {
                throw new IllegalStateException("Unresolved link in %s/%s: %s".formatted(module, pagesDir.relativize(file), match.group()));
            }
            return target;
        }

        private String toXrefTarget(AnchorTarget target) {
            var page = pagesDir.relativize(target.file()).toString();
            return target instanceof AnchorTarget.Fragment fragment ? page + "#" + fragment.ref() : page;
        }

        private void replaceAll(String regex, Function<MatchResult, String> replacer) {
            text = Pattern.compile(regex).matcher(text).replaceAll(match -> Matcher.quoteReplacement(replacer.apply(match)));
        }

        void save() throws IOException {
            Files.createDirectories(file.getParent());
            Files.writeString(file, text);
        }

        private void transformLines(UnaryOperator<List<String>> transformer) {
            List<String> lines = text.lines().collect(toCollection(ArrayList::new));
            lines = transformer.apply(lines);
            text = String.join("\n", lines);
            if (!text.endsWith("\n")) {
                text += "\n";
            }
        }

        @Override
        public String toString() {
            return "Document{" +
                    "file=" + file +
                    '}';
        }
    }

    sealed interface AnchorTarget {
        Path file();

        record Page(Path file) implements AnchorTarget {
        }

        record Fragment(Path file, String title, String ref) implements AnchorTarget {
        }
    }
}
