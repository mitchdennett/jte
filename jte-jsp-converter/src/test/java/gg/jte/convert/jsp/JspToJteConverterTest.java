package gg.jte.convert.jsp;

import gg.jte.convert.IoUtils;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;


class JspToJteConverterTest {
    @TempDir
    Path tempDir;

    String usecase;
    Path jspRoot;
    Path jteRoot;

    String[] notConvertedTags;

    @Test
    void simpleTag() {
        givenUsecase("simpleTag");
        whenJspTagIsConverted("simple.tag", "tag/simple.jte");
        thenConversionIsAsExpected();
    }

    @Test
    void simpleTagWithCommentBetweenParams() {
        givenUsecase("simpleTagWithCommentBetweenParams");
        whenJspTagIsConverted("simple.tag", "tag/simple.jte");
        thenConversionIsAsExpected();
    }

    @Test
    void simpleTag_kebabCase() {
        givenUsecase("simpleTag");
        Throwable throwable = catchThrowable(() -> whenJspTagIsConverted("simple.tag", "tag/not-so-simple.jte"));
        assertThat(throwable).isInstanceOf(IllegalArgumentException.class).hasMessage("Illegal jte tag name 'tag/not-so-simple.jte'. Tag names should be camel case.");
    }

    @Test
    void simpleTagWithTwoOutputsAfterEachOther() {
        givenUsecase("simpleTagWithTwoOutputsAfterEachOther");
        whenJspTagIsConverted("simple.tag", "tag/simple.jte");
        thenConversionIsAsExpected();
    }

    @Test
    void simpleTagWithIfStatement() {
        givenUsecase("simpleTagWithIfStatement");
        whenJspTagIsConverted("simple.tag", "tag/simple.jte");
        thenConversionIsAsExpected();
    }

    @Test
    void simpleTagWithChooseStatement() {
        givenUsecase("simpleTagWithChooseStatement");
        whenJspTagIsConverted("simple.tag", "tag/simple.jte");
        thenConversionIsAsExpected();
    }

    @Test
    void simpleTagWithVariable() {
        givenUsecase("simpleTagWithVariable");
        whenJspTagIsConverted("simple.tag", "tag/simple.jte");
        thenConversionIsAsExpected();
    }

    @Test
    void simpleTagWithForEach() {
        givenUsecase("simpleTagWithForEach");
        whenJspTagIsConverted("simple.tag", "tag/simple.jte");
        thenConversionIsAsExpected();
    }

    @Test
    void simpleTagWithFormatMessage() {
        givenUsecase("simpleTagWithFormatMessage");
        whenJspTagIsConverted("simple.tag", "tag/simple.jte");
        thenConversionIsAsExpected();
    }

    @Test
    void simpleTagWithUsages() {
        givenUsecase("simpleTagWithUsages");
        whenJspTagIsConverted("my/simple.tag", "tag/my/simple.jte");
        thenConversionIsAsExpected();
    }

    @Test
    void simpleJspWithAlreadyConvertedTag() {
        givenUsecase("simpleJspWithAlreadyConvertedTag");
        whenJspTagIsConverted("usage.jsp", "usage.jte");
        thenConversionIsAsExpected();
    }

    @Test
    void simpleTagWithNotYetConvertedTag() {
        givenUsecase("simpleTagWithNotYetConvertedTag");
        Throwable throwable = catchThrowable(() -> whenJspTagIsConverted("my/simple.tag", "tag/my/simple.jte"));
        assertThat(throwable).isInstanceOf(IllegalStateException.class).hasMessage("The tag <my:simple-dependency/> is used by this tag and not converted to jte yet. You should convert <my:simple-dependency/> first. If this is a tag that should be always converted by hand, implement getNotConvertedTags() and add it there.");
    }

    @Test
    void simpleTagWithNotYetConvertedTag_allowed() {
        notConvertedTags = new String[]{"my:simple-dependency"};

        givenUsecase("simpleTagWithNotYetConvertedTag");
        whenJspTagIsConverted("my/simple.tag", "tag/my/simple.jte");
        thenConversionIsAsExpected();
    }

    void givenUsecase(String usecase) {
        jspRoot = tempDir.resolve("jsp");
        jteRoot = tempDir.resolve("jte");

        IoUtils.copyDirectory(Path.of("testdata", usecase, "before"), tempDir);

        this.usecase = usecase;
    }

    void whenJspTagIsConverted(String jspTag, String jteTag) {
        JspToJteConverter converter = new MyConverter();
        converter.convertTag(jspTag, jteTag, parser -> {
            parser.setPrefix("@import static example.JteContext.*\n");
            parser.setIndentationChar(' ');
            parser.setIndentationCount(4);
            parser.setLineSeparator(System.lineSeparator());
        });
    }

    private void thenConversionIsAsExpected() {
        Path expected = Path.of("testdata", usecase, "after");
        Path actual = tempDir;

        try (Stream<Path> stream = Files.walk(expected)) {
            stream.filter(p -> !Files.isDirectory(p)).forEach(expectedFile -> {
                Path actualFile = actual.resolve(expected.relativize(expectedFile));
                assertThat(actualFile).exists();
                assertThat(actualFile).hasSameTextualContentAs(expectedFile);
            });
        } catch (IOException e){
            throw new UncheckedIOException(e);
        }
    }

    private class MyConverter extends JspToJteConverter {

        public MyConverter() {
            super(jspRoot, jteRoot, "my:jte");
        }

        @Override
        protected String[] getNotConvertedTags() {
            return notConvertedTags;
        }
    }
}