package com.ruoyi.system.kb.graph.extract;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;
import com.ruoyi.system.kb.graph.GraphEntity;
import com.ruoyi.system.kb.graph.GraphRelation;
import com.ruoyi.system.kb.graph.extract.ExtractResultParser.ExtractResult;
import com.ruoyi.system.kb.graph.provenance.GraphExtractOutcome;

class ExtractResultValidatorTest
{
    @Test
    void repairsMissingEndpoints()
    {
        ExtractResult r = new ExtractResult();
        GraphEntity e = new GraphEntity();
        e.setName("A");
        r.setEntities(List.of(e));
        GraphRelation rel = new GraphRelation();
        rel.setSourceName("A");
        rel.setTargetName("B");
        rel.setKeywords("关联");
        r.setRelations(List.of(rel));

        ExtractResult out = ExtractResultValidator.validate(r, GraphExtractionProfile.defaults());
        assertEquals(GraphExtractOutcome.SUCCESS, out.getOutcome());
        assertEquals(1, out.getRepairedEndpoints());
        assertTrue(out.getEntities().stream().anyMatch(x -> "B".equals(x.getName())));
    }

    @Test
    void emptyIsValidEmptyNotLlmFailed()
    {
        ExtractResult r = new ExtractResult();
        ExtractResult out = ExtractResultValidator.validate(r, GraphExtractionProfile.defaults());
        assertEquals(GraphExtractOutcome.VALID_EMPTY, out.getOutcome());
    }

    @Test
    void parseFailedPreserved()
    {
        ExtractResult r = new ExtractResult();
        r.setOutcome(GraphExtractOutcome.PARSE_FAILED);
        ExtractResult out = ExtractResultValidator.validate(r, GraphExtractionProfile.defaults());
        assertEquals(GraphExtractOutcome.PARSE_FAILED, out.getOutcome());
    }
}
