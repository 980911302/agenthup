package com.ruoyi.system.kb.eval;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import com.ruoyi.system.kb.eval.MultiModeEvalHarness.ModeAggregate;
import com.ruoyi.system.kb.eval.MultiModeEvalHarness.QueryCase;

class MultiModeEvalHarnessTest
{
    @Test
    void localBeatsBasicOnMultiHop()
    {
        Map<String, List<Boolean>> r1 = new LinkedHashMap<>();
        r1.put("basic", List.of(false, false, true, false));
        r1.put("local", List.of(true, true, false, false));

        Map<String, List<Boolean>> r2 = new LinkedHashMap<>();
        r2.put("basic", List.of(false, true, false));
        r2.put("local", List.of(true, false, false));

        List<QueryCase> cases = List.of(
            MultiModeEvalHarness.caseOf("q1", "multi_hop", 2, r1),
            MultiModeEvalHarness.caseOf("q2", "multi_hop", 1, r2));

        Map<String, ModeAggregate> agg = MultiModeEvalHarness.compare(cases, 10);
        double lift = MultiModeEvalHarness.lift(agg.get("local"), agg.get("basic"));
        assertTrue(lift > 0, "local should lift recall over basic, lift=" + lift);
    }

    @Test
    void basicNoRegressionGate()
    {
        ModeAggregate baseline = new ModeAggregate();
        baseline.recallAt10 = 0.8;
        ModeAggregate nowOk = new ModeAggregate();
        nowOk.recallAt10 = 0.78;
        ModeAggregate nowBad = new ModeAggregate();
        nowBad.recallAt10 = 0.5;

        assertTrue(MultiModeEvalHarness.basicNoRegression(nowOk, baseline, 0.05));
        assertFalse(MultiModeEvalHarness.basicNoRegression(nowBad, baseline, 0.05));
    }
}
