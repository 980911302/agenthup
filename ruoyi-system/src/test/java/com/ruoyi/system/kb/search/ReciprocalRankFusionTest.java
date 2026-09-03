package com.ruoyi.system.kb.search;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;
import com.ruoyi.system.kb.search.ReciprocalRankFusion.FusedHit;

class ReciprocalRankFusionTest
{
    @Test
    void clearWinnerWhenTopInBoth()
    {
        List<String> basic = List.of("X", "Y", "Z");
        List<String> local = List.of("X", "W", "Y");
        List<FusedHit<String>> fused = ReciprocalRankFusion.fuse(
            List.of(basic, local), s -> s, 60, 5);

        assertEquals("X", fused.get(0).item);
        double expected = 1.0 / (60 + 1) + 1.0 / (60 + 1);
        assertEquals(expected, fused.get(0).score, 1e-9);
        assertTrue(fused.stream().anyMatch(f -> "Y".equals(f.item)));
        assertTrue(fused.stream().anyMatch(f -> "W".equals(f.item)));
    }

    @Test
    void singleChannelPreservesOrder()
    {
        List<String> only = List.of("A", "B", "C");
        List<FusedHit<String>> fused = ReciprocalRankFusion.fuse(
            List.of(only), s -> s, 60, 10);
        assertEquals(List.of("A", "B", "C"),
            fused.stream().map(f -> f.item).toList());
        assertEquals(1.0 / 61, fused.get(0).score, 1e-9);
    }

    @Test
    void emptyLists()
    {
        assertTrue(ReciprocalRankFusion.fuse(List.of(), s -> s, 60, 5).isEmpty());
        assertTrue(ReciprocalRankFusion.fuse(List.of(List.of()), s -> s, 60, 5).isEmpty());
    }

    @Test
    void topNTruncates()
    {
        List<String> a = List.of("1", "2", "3", "4");
        List<FusedHit<String>> fused = ReciprocalRankFusion.fuse(
            List.of(a), s -> s, 60, 2);
        assertEquals(2, fused.size());
        assertEquals("1", fused.get(0).item);
        assertEquals("2", fused.get(1).item);
    }

    @Test
    void channelRanksRecorded()
    {
        List<String> basic = List.of("A", "B");
        List<String> local = List.of("B", "C");
        List<FusedHit<String>> fused = ReciprocalRankFusion.fuse(
            List.of(basic, local), s -> s, 60, 5);
        FusedHit<String> b = fused.stream().filter(f -> "B".equals(f.item)).findFirst().orElseThrow();
        assertEquals(2, b.channelRanks.get(0)); // basic rank 2
        assertEquals(1, b.channelRanks.get(1)); // local rank 1
    }
}
