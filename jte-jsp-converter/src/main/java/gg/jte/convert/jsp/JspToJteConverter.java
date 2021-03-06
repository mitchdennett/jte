package gg.jte.convert.jsp;

import gg.jte.convert.IoUtils;
import gg.jte.convert.cc.CamelCaseConverter;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

public class JspToJteConverter {

    private static final Pattern UNCONVERTED_TAG_REFERENCES = Pattern.compile("<[a-zA-Z\\-]+:[a-zA-Z\\-]+\\b");

    public static void convertFromIntelliJPlugin(String[] commandLineArgs, JspToJteConverter converter, Consumer<JspParser> parserSetup) {
        if (commandLineArgs.length < 1) {
            throw new IllegalArgumentException("Missing JSP file, it should be the first argument on the command line.");
        }

        Path jspFile = Path.of(commandLineArgs[0]);
        jspFile = converter.jspRoot.toAbsolutePath().relativize(jspFile);

        String jteFile = converter.suggestJteFile(jspFile.toString().replace('\\', '/'));

        converter.convertTag(jspFile.toString(), jteFile, parserSetup);
    }

    private final Path jspRoot;
    private final Path jteRoot;
    private final String jteTag;

    public JspToJteConverter(Path jspRoot, Path jteRoot, String jteTag) {
        this.jspRoot = jspRoot;
        this.jteRoot = jteRoot;
        this.jteTag = jteTag;
    }

    public void convertTag(String jspTag, String jteTag, Consumer<JspParser> parserSetup) {
        checkJteName(jteTag);
        convertTag(jspRoot.resolve(jspTag), jteRoot.resolve(jteTag), parserSetup);
    }

    @SuppressWarnings("unused")
    public void replaceUsages(String jspTag, String jteTag) {
        checkJteName(jteTag);
        replaceUsages(jspRoot.resolve(jspTag), jteRoot.resolve(jteTag));
    }

    public void replaceUsages(Path jspTag, Path jteTag) {
        String oldJspTagPrefix = extractTagPrefix(jspTag);
        String newJteFile = jteRoot.relativize(jteTag).toString().replace('\\', '/');

        IoUtils.deleteFile(jspTag);

        try (Stream<Path> stream = Files.walk(jspRoot)) {
            stream
                  .filter(Files::isRegularFile)
                  .filter(p -> !Files.isDirectory(p))
                  .filter(p -> {
                      String fileName = p.toString();
                      return fileName.endsWith(".jsp") || fileName.endsWith(".jsp.inc") || fileName.endsWith(".tag");
                  }).forEach(jspFile -> replaceUsages(jspFile, oldJspTagPrefix, newJteFile));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private void convertTag(Path jspTag, Path jteTag, Consumer<JspParser> parserSetup) {
        JspParser parser = new JspParser(this.jteTag);
        if (parserSetup != null) {
            parserSetup.accept(parser);
        }
        String jte = parser.convert(IoUtils.readFile(jspTag));

        System.out.println(jte);

        checkDependencies(jte);

        IoUtils.writeFile(jteTag, jte);

        replaceUsages(jspTag, jteTag);
    }

    private void replaceUsages(Path jspFile, String oldJspTagPrefix, String newJteFile) {
        boolean modified = false;

        StringBuilder jspContent = new StringBuilder(IoUtils.readFile(jspFile));

        int lastIndex = 0;

        do {
            lastIndex = jspContent.indexOf(oldJspTagPrefix, lastIndex);
            if (lastIndex >= 0 && lastIndex < jspContent.length()) {
                char character = jspContent.charAt(lastIndex + oldJspTagPrefix.length());
                if (Character.isWhitespace(character) || character == '/') {
                    jspContent.replace(lastIndex, lastIndex + oldJspTagPrefix.length(), "<" + jteTag + " jte=\"" + newJteFile + "\"");
                    modified = true;
                } else {
                    ++lastIndex;
                }
            } else {
                break;
            }
        } while (true);

        if (modified) {
            IoUtils.writeFile(jspFile, jspContent.toString());
        }
    }

    private String extractTagPrefix(Path jspTag) {
        String fileName = jspTag.getFileName().toString();
        String tagName = fileName.substring(0, fileName.indexOf('.'));

        String namespace = jspTag.getParent().getFileName().toString();
        if ("tags".equals(namespace)) {
            namespace = "include";
        }

        return "<" + namespace + ":" + tagName;
    }

    protected String suggestJteFile(String jspFile) {
        int fileSeparatorIndex = jspFile.indexOf('.');
        if (fileSeparatorIndex == -1) {
            throw new IllegalArgumentException("JSP file without file extension " + jspFile);
        }

        String jspFileWithoutExtension = jspFile.substring(0, fileSeparatorIndex);
        String jspFileExtension = jspFile.substring(fileSeparatorIndex);

        String jteDirectory = suggestJteDirectory(jspFileWithoutExtension, jspFileExtension);

        StringBuilder jteFile = new StringBuilder();
        if (!jteDirectory.isBlank()) {
            jteFile.append(jteDirectory);
            jteFile.append('/');
        }

        jteFile.append(skipDirectoryIfRequired(jspFileWithoutExtension));
        CamelCaseConverter.convertTo(jteFile);
        jteFile.append(".jte");

        return jteFile.toString();
    }

    protected String skipDirectoryIfRequired(String jspFileWithoutExtension) {
        if (jspFileWithoutExtension.startsWith("tags/") || jspFileWithoutExtension.startsWith("tag/")) {
            return skipFirstDirectory(jspFileWithoutExtension);
        }

        if (jspFileWithoutExtension.startsWith("layouts/") || jspFileWithoutExtension.startsWith("layout/")) {
            return skipFirstDirectory(jspFileWithoutExtension);
        }

        return jspFileWithoutExtension;
    }

    protected String skipFirstDirectory(String jspFileWithoutExtension) {
        return jspFileWithoutExtension.substring(jspFileWithoutExtension.indexOf('/') + 1);
    }

    protected String suggestJteDirectory(String jspFileWithoutExtension, String jspFileExtension) {
        if (jspFileWithoutExtension.startsWith("layouts/") || jspFileWithoutExtension.startsWith("layout/")) {
            return "layout";
        }

        if (!".jsp".equals(jspFileExtension)) {
            return "tag";
        }
        return "";
    }

    protected void checkJteName(String name) {
        if (name.contains("-")) {
            throw new IllegalArgumentException("Illegal jte tag name '" + name + "'. Tag names should be camel case.");
        }
    }

    protected void checkDependencies(String jteCode) {
        List<String> unresolvedJspTags = findUnresolvedJspTags(jteCode);
        if (unresolvedJspTags.isEmpty()) {
            return;
        }

        Set<String> notConvertedTagsSet = getNotConvertedTagsAsSet();

        for (String unresolvedJspTag : unresolvedJspTags) {
            if (!notConvertedTagsSet.contains(unresolvedJspTag)) {
                throw new IllegalStateException("The tag " + unresolvedJspTag + "/> is used by this tag and not converted to jte yet. You should convert " + unresolvedJspTag + "/> first. If this is a tag that should be always converted by hand, implement getNotConvertedTags() and add it there.");
            }
        }
    }

    private Set<String> getNotConvertedTagsAsSet() {
        String[] notConvertedTags = getNotConvertedTags();
        Set<String> notConvertedTagsSet = new HashSet<>();
        if (notConvertedTags != null) {
            for (String notConvertedTag : notConvertedTags) {
                notConvertedTagsSet.add("<" + notConvertedTag);
            }
        }
        return notConvertedTagsSet;
    }

    protected List<String> findUnresolvedJspTags(String jteCode) {
        List<String> result = new ArrayList<>();

        Matcher matcher = UNCONVERTED_TAG_REFERENCES.matcher(jteCode);

        while (matcher.find()) {
            result.add(matcher.group());
        }

        return result;
    }

    protected String[] getNotConvertedTags() {
        return null;
    }
}
