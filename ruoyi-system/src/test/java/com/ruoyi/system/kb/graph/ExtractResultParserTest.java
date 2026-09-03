package com.ruoyi.system.kb.graph;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import com.ruoyi.system.kb.graph.extract.ExtractResultParser;
import com.ruoyi.system.kb.graph.extract.ExtractResultParser.ExtractResult;

class ExtractResultParserTest
{
    @Test
    void parsesFencedJson()
    {
        String raw = """
            ```json
            {
              "entities": [{"name":"一致性哈希","type":"技术","description":"分布式负载技术"}],
              "relationships": [{"source":"一致性哈希","target":"缓存集群","keywords":"用于","description":"路由"}]
            }
            ```
            """;
        ExtractResult r = ExtractResultParser.parse(raw);
        assertEquals(1, r.getEntities().size());
        assertEquals("一致性哈希", r.getEntities().get(0).getName());
        assertEquals(1, r.getRelations().size());
        assertEquals("缓存集群", r.getRelations().get(0).getTargetName());
    }

    @Test
    void toleratesTrailingComma()
    {
        String raw = """
            {
              "entities": [{"name":"A","type":"T","description":"d"},],
              "relationships": []
            }
            """;
        ExtractResult r = ExtractResultParser.parse(raw);
        assertEquals(1, r.getEntities().size());
        assertEquals("A", r.getEntities().get(0).getName());
    }

    @Test
    void toleratesTruncatedJson()
    {
        String raw = "{\"entities\":[{\"name\":\"截断实体\",\"type\":\"T\",\"description\":\"x\"";
        ExtractResult r = ExtractResultParser.parse(raw);
        // 补全后应能解析出实体
        assertFalse(r.getEntities().isEmpty());
        assertEquals("截断实体", r.getEntities().get(0).getName());
    }

    @Test
    void emptyOnGarbage()
    {
        ExtractResult r = ExtractResultParser.parse("这不是 JSON");
        assertTrue(r.getEntities().isEmpty());
        assertTrue(r.getRelations().isEmpty());
        assertEquals(com.ruoyi.system.kb.graph.provenance.GraphExtractOutcome.PARSE_FAILED, r.getOutcome());
    }

    @Test
    void validEmptyArraysAreNotParseFailed()
    {
        ExtractResult r = ExtractResultParser.parse("{\"entities\":[],\"relationships\":[]}");
        assertTrue(r.getEntities().isEmpty());
        assertTrue(r.getRelations().isEmpty());
        assertNull(r.getOutcome());
    }
}
