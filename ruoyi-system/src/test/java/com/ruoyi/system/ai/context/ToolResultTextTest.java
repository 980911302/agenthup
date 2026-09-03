package com.ruoyi.system.ai.context;

import org.junit.jupiter.api.Test;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 「工具结果进上下文时长什么样」这条口径的不变量。
 *
 * <p>核心是一条:<b>本轮所见 == 跨轮重建 == tokens 估算的依据</b>。
 * 这三者原先各写各的公式,行数维度只加在了本轮那一份,于是行多字符少的输出
 * (ls -R / find / 大 CSV)会在下一轮自己长回全量。
 */
class ToolResultTextTest
{
    private static final int MAX_LINES = ToolResultText.DEFAULT_MAX_LINES;
    private static final int MAX_CHARS = ToolResultText.DEFAULT_MAX_CHARS;

    /** 行多字符少:只卡字符会整段放行,必须靠行数维度拦住。 */
    @Test
    void lineHeavyOutputIsCappedEvenWhenCharsAreUnderLimit()
    {
        String lineHeavy = "a\n".repeat(5000); // 10000 字符 < 30000,但 5000 行 > 2000

        assertThat(lineHeavy.length()).isLessThan(MAX_CHARS);
        String capped = ToolResultText.cap(lineHeavy, MAX_LINES, MAX_CHARS);

        assertThat(capped).isNotEqualTo(lineHeavy);
        assertThat(capped.split("\n", -1).length).isLessThanOrEqualTo(MAX_LINES);
        assertThat(capped).contains("中间省略").contains("行");
    }

    /**
     * 幂等 —— 整条不变量成立的前提。
     *
     * <p>跨轮重建拿到的是库里的全文,会再跑一次 cap;若不幂等,同一条工具结果在第二轮、
     * 第三轮会越裁越短,消息前缀每轮都变,上游 KV-cache 从这里断掉。
     */
    @Test
    void capIsIdempotent()
    {
        String lineHeavy = "行内容\n".repeat(5000);
        String charHeavy = "x".repeat(80_000);
        String both = ("y".repeat(100) + "\n").repeat(4000);

        for (String raw : new String[] { lineHeavy, charHeavy, both })
        {
            String once = ToolResultText.cap(raw, MAX_LINES, MAX_CHARS);
            assertThat(ToolResultText.cap(once, MAX_LINES, MAX_CHARS))
                    .as("cap(cap(x)) 必须等于 cap(x)")
                    .isEqualTo(once);
        }
    }

    /** 两道截断都不触发时原样返回,正常结果不该被无谓地复制或改写。 */
    @Test
    void shortResultPassesThroughUnchanged()
    {
        String small = "第一行\n第二行";
        assertThat(ToolResultText.cap(small, MAX_LINES, MAX_CHARS)).isSameAs(small);
    }

    /** 上限 <= 0 表示不限:ToolBudget 缺席时不能凭空把结果裁掉。 */
    @Test
    void nonPositiveCapsMeanNoLimit()
    {
        String raw = "a\n".repeat(5000);

        assertThat(ToolResultText.cap(raw, 0, 0)).isSameAs(raw);
        assertThat(ToolResultText.cap(raw, -1, -1)).isSameAs(raw);
        assertThat(ToolResultText.cap(null, MAX_LINES, MAX_CHARS)).isNull();
    }

    /** 先行后字符:反过来会先把长行切碎,行数统计跟着失真。 */
    @Test
    void lineCapAppliesBeforeCharCap()
    {
        String raw = ("z".repeat(50) + "\n").repeat(3000); // 行数与字符数同时超

        String capped = ToolResultText.cap(raw, MAX_LINES, MAX_CHARS);

        assertThat(capped.length()).isLessThanOrEqualTo(MAX_CHARS);
        assertThat(capped).contains("中间省略");
    }

    /** 字符维度的省略提示要报出确切数量,模型据此判断信息不全。 */
    @Test
    void charCapReportsOmittedCount()
    {
        String raw = "q".repeat(1000);

        String capped = ToolResultText.cap(raw, 0, 200);

        assertThat(capped.length()).isLessThanOrEqualTo(200);
        assertThat(capped).contains("中间省略").contains("字符");
        assertThat(capped).startsWith("q").endsWith("q");
    }

    // —— 以下从 ToolBudgetTest 迁来:截断算法的用例跟着算法走,测试布局与代码布局保持一致 ——

    @Test
    void truncateMiddle_preservesHeadAndTail()
    {
        String head = "HEAD_CONTENT_AAA";
        String middle = "x".repeat(200);
        String tail = "TAIL_CONTENT_ZZZ";
        String full = head + middle + tail;
        int max = 80;
        String capped = ToolResultText.truncateMiddle(full, max);

        assertTrue(capped.length() <= max, "capped length=" + capped.length());
        assertTrue(capped.startsWith("HEAD_"), "should keep head");
        assertTrue(capped.endsWith("_ZZZ"), "should keep tail");
        assertTrue(capped.contains("中间省略"), "should insert middle marker");
        assertTrue(capped.contains("如需被省略的部分，请缩小查询范围后重新调用本工具"), "marker wording");
    }

    @Test
    void truncateMiddle_noOpWhenWithinLimit()
    {
        String s = "short";
        assertEquals(s, ToolResultText.truncateMiddle(s, 100));
        assertNull(ToolResultText.truncateMiddle(null, 10));
    }

    @Test
    void truncateMiddle_reportsExactOmittedCount()
    {
        String s = "X".repeat(100_000);
        int max = 30_000;
        String capped = ToolResultText.truncateMiddle(s, max);

        assertTrue(capped.length() <= max, "总长必须不超 max, 实际=" + capped.length());

        Matcher m = Pattern.compile("\n…\\[中间省略 (\\d+) 字符。如需被省略的部分，请缩小查询范围后重新调用本工具\\]…\n")
                .matcher(capped);
        assertTrue(m.find(), "marker 应完整出现在结果中");
        long reported = Long.parseLong(m.group(1));

        int keptFromSource = capped.length() - m.group(0).length();
        long actualOmitted = s.length() - keptFromSource;
        assertEquals(actualOmitted, reported,
                "marker 声称省略 " + reported + ",实际省略 " + actualOmitted);
    }

    @Test
    void truncateLines_keepsHeadAndTail()
    {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 5000; i++)
        {
            sb.append("第").append(i).append("行\n");
        }

        String out = ToolResultText.truncateLines(sb.toString(), 2000);

        assertTrue(out.contains("第0行"));
        assertTrue(out.contains("第4999行"));
        assertFalse(out.contains("第2500行"));
        assertTrue(out.contains("省略"));
        assertTrue(out.lines().count() <= 2001);
    }

    @Test
    void truncateLines_underLimit_returnsSame()
    {
        String value = "第一行\n第二行\n第三行";
        assertSame(value, ToolResultText.truncateLines(value, 2000));
    }

    @Test
    void truncateLines_nullSafe()
    {
        assertNull(ToolResultText.truncateLines(null, 100));
    }
}
