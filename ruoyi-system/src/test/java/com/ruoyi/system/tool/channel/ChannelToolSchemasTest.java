package com.ruoyi.system.tool.channel;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChannelToolSchemasTest
{
    @Test
    void nameRegex_acceptsAndRejectsBoundaries()
    {
        new ChannelToolDef("a", "d", ChannelToolSchemas.DEFAULT_SCHEMA);
        new ChannelToolDef("_x", "d", ChannelToolSchemas.DEFAULT_SCHEMA);
        new ChannelToolDef("pickFile", "d", ChannelToolSchemas.DEFAULT_SCHEMA);
        new ChannelToolDef("A" + "x".repeat(63), "d", ChannelToolSchemas.DEFAULT_SCHEMA);

        assertThrows(IllegalArgumentException.class,
                () -> new ChannelToolDef("1abc", "d", ChannelToolSchemas.DEFAULT_SCHEMA));
        assertThrows(IllegalArgumentException.class,
                () -> new ChannelToolDef("ab.", "d", ChannelToolSchemas.DEFAULT_SCHEMA));
        assertThrows(IllegalArgumentException.class,
                () -> new ChannelToolDef("", "d", ChannelToolSchemas.DEFAULT_SCHEMA));
        assertThrows(IllegalArgumentException.class,
                () -> new ChannelToolDef("A" + "x".repeat(64), "d", ChannelToolSchemas.DEFAULT_SCHEMA));
    }

    @Test
    void descriptionBlank_rejected()
    {
        List<String> skipped = new ArrayList<>();
        List<ChannelToolDef> defs = ChannelToolSchemas.parseAndNormalize(
                "[{\"name\":\"ping\",\"description\":\"  \",\"parameters\":{}}]", skipped);
        assertTrue(defs.isEmpty());
        assertEquals(List.of("ping"), skipped);
    }

    @Test
    void schemaNonObject_fallsBackToEmptyObject()
    {
        List<String> skipped = new ArrayList<>();
        List<ChannelToolDef> defs = ChannelToolSchemas.parseAndNormalize(
                "[{\"name\":\"ping\",\"description\":\"p\",\"parameters\":[]}]", skipped);
        assertEquals(1, defs.size());
        assertEquals(ChannelToolSchemas.DEFAULT_SCHEMA, defs.get(0).inputSchemaJson());
    }

    @Test
    void canonicalize_insensitiveToKeyOrder()
    {
        String a = ChannelToolSchemas.canonicalize(
                "{\"type\":\"object\",\"properties\":{\"z\":{\"type\":\"string\"},\"a\":{\"type\":\"number\"}}}");
        String b = ChannelToolSchemas.canonicalize(
                "{\"properties\":{\"a\":{\"type\":\"number\"},\"z\":{\"type\":\"string\"}},\"type\":\"object\"}");
        assertEquals(a, b);
        assertTrue(a.indexOf("\"a\"") < a.indexOf("\"z\""));
    }

    @Test
    void parseAndNormalize_sortsByNameRegardlessOfInputOrder()
    {
        List<String> skipped = new ArrayList<>();
        List<ChannelToolDef> defs = ChannelToolSchemas.parseAndNormalize(
                "["
                        + "{\"name\":\"zeta\",\"description\":\"z\",\"parameters\":{\"type\":\"object\"}},"
                        + "{\"name\":\"alpha\",\"description\":\"a\",\"parameters\":{\"type\":\"object\"}}"
                        + "]", skipped);
        assertEquals(List.of("alpha", "zeta"), defs.stream().map(ChannelToolDef::name).toList());
        assertTrue(skipped.isEmpty());
        String json = ChannelToolSchemas.serialize(defs);
        assertTrue(json.indexOf("alpha") < json.indexOf("zeta"));
    }

    @Test
    void duplicateName_secondSkipped()
    {
        List<String> skipped = new ArrayList<>();
        List<ChannelToolDef> defs = ChannelToolSchemas.parseAndNormalize(
                "["
                        + "{\"name\":\"ping\",\"description\":\"one\",\"parameters\":{}},"
                        + "{\"name\":\"ping\",\"description\":\"two\",\"parameters\":{}}"
                        + "]", skipped);
        assertEquals(1, defs.size());
        assertEquals("one", defs.get(0).description());
        assertEquals(List.of("ping"), skipped);
    }

    @Test
    void parseQuietly_badJson_empty()
    {
        assertTrue(ChannelToolSchemas.parseQuietly("not-json").isEmpty());
        assertTrue(ChannelToolSchemas.parseQuietly(null).isEmpty());
    }
}
