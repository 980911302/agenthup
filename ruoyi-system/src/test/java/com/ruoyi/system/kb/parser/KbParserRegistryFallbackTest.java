package com.ruoyi.system.kb.parser;

import static org.junit.jupiter.api.Assertions.*;

import java.io.File;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

class KbParserRegistryFallbackTest
{
    @TempDir
    Path tempDir;

    @Test
    void usesFallbackWhenPrimaryParserThrows() throws Exception
    {
        KbParserRegistry registry = new KbParserRegistry();
        ReflectionTestUtils.setField(registry, "parsers", List.of(new BrokenParser(), new StubFallbackParser()));

        IrDoc doc = registry.parse(tempDir.resolve("sample.txt").toFile(), "sample.txt");

        assertTrue(doc.isFallbackUsed());
        assertEquals("StubFallbackParser", doc.getExtractor());
        assertEquals("fallback content", doc.getBlocks().get(0).getText());
    }

    @Test
    void keepsSuccessfulPrimaryParser()
        throws Exception
    {
        KbParserRegistry registry = new KbParserRegistry();
        ReflectionTestUtils.setField(registry, "parsers", List.of(new GoodParser(), new StubFallbackParser()));

        IrDoc doc = registry.parse(tempDir.resolve("sample.txt").toFile(), "sample.txt");

        assertFalse(doc.isFallbackUsed());
        assertEquals("GoodParser", doc.getExtractor());
        assertEquals("primary content", doc.getBlocks().get(0).getText());
    }

    @Test
    void treatsNullPrimaryResultAsFailureAndUsesFallback() throws Exception
    {
        KbParserRegistry registry = new KbParserRegistry();
        ReflectionTestUtils.setField(registry, "parsers", List.of(new NullParser(), new StubFallbackParser()));

        IrDoc doc = registry.parse(tempDir.resolve("sample.txt").toFile(), "sample.txt");

        assertTrue(doc.isFallbackUsed());
        assertEquals("fallback content", doc.getBlocks().get(0).getText());
    }

    private static IrDoc doc(String text)
    {
        IrDoc doc = ParserSupport.newDocument("sample.txt", "txt");
        ParserSupport.addBlock(doc, 0, text, "paragraph", List.of(), null, null);
        return doc;
    }

    private static class BrokenParser implements KbParser
    {
        public boolean supports(String extension) { return "txt".equals(extension); }
        public IrDoc parse(File file, String fileName) { throw new IllegalStateException("broken"); }
    }

    private static class GoodParser implements KbParser
    {
        public boolean supports(String extension) { return "txt".equals(extension); }
        public IrDoc parse(File file, String fileName) { return doc("primary content"); }
    }

    private static class NullParser implements KbParser
    {
        public boolean supports(String extension) { return "txt".equals(extension); }
        public IrDoc parse(File file, String fileName) { return null; }
    }

    private static class StubFallbackParser implements KbParser
    {
        public boolean isFallback() { return true; }
        public boolean supports(String extension) { return "txt".equals(extension); }
        public IrDoc parse(File file, String fileName) { return doc("fallback content"); }
    }
}
