package com.ruoyi.system.kb.graph.textunit;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import com.ruoyi.system.domain.KbChunk;
import com.ruoyi.system.domain.KbGraphTextUnit;
import com.ruoyi.system.kb.KbConstants;
import com.ruoyi.system.kb.chunker.TokenCounter;

/**
 * 从有序 LEAF chunk 派生 Graph TextUnit。
 * <p>按文档顺序 + 连续 headingPath 聚合；超 targetSize 切分；表格叶子已带表头则原样继承。
 * 参数变更只影响图抽取，不碰 embedding。
 */
public final class GraphTextUnitBuilder
{
    public static final String GRAPH_UNIT_VERSION = "1";

    private GraphTextUnitBuilder()
    {
    }

    public static String paramsHash(int targetSize, int overlap)
    {
        String raw = GRAPH_UNIT_VERSION + "|" + targetSize + "|" + overlap;
        try
        {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] dig = md.digest(raw.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 16; i++)
            {
                sb.append(String.format("%02x", dig[i]));
            }
            return sb.toString();
        }
        catch (Exception e)
        {
            return Integer.toHexString(raw.hashCode());
        }
    }

    /**
     * @param leaves      文档序 LEAF
     * @param targetSize  目标 token（建议 200–500）
     * @param maxSize     硬上限，默认 target*1.4
     */
    public static List<KbGraphTextUnit> build(List<KbChunk> leaves, int targetSize, int maxSize)
    {
        if (leaves == null || leaves.isEmpty())
        {
            return List.of();
        }
        int target = Math.max(50, targetSize);
        int max = Math.max(target, maxSize);
        List<KbGraphTextUnit> units = new ArrayList<>();
        List<KbChunk> run = new ArrayList<>();
        String runPath = null;
        String runType = null;

        for (KbChunk leaf : leaves)
        {
            if (leaf == null || leaf.getContent() == null || leaf.getContent().isBlank())
            {
                continue;
            }
            String path = leaf.getHeadingPath();
            String type = normalizeType(leaf.getBlockType());
            // 路径或结构类型变化时切分，避免正文与表格混装
            if (!run.isEmpty() && (!Objects.equals(runPath, path) || !Objects.equals(runType, type)))
            {
                packRun(units, run, target, max);
                run = new ArrayList<>();
            }
            if (run.isEmpty())
            {
                runPath = path;
                runType = type;
            }
            run.add(leaf);
        }
        packRun(units, run, target, max);

        for (int i = 0; i < units.size(); i++)
        {
            units.get(i).setOrdinal(i);
        }
        return units;
    }

    public static List<KbGraphTextUnit> buildDefault(List<KbChunk> leaves)
    {
        return build(leaves, 350, 500);
    }

    private static void packRun(List<KbGraphTextUnit> out, List<KbChunk> run, int target, int max)
    {
        if (run == null || run.isEmpty())
        {
            return;
        }
        List<KbChunk> group = new ArrayList<>();
        int tokens = 0;
        for (KbChunk leaf : run)
        {
            int t = leaf.getTokenCount() != null && leaf.getTokenCount() > 0
                ? leaf.getTokenCount() : TokenCounter.estimate(leaf.getContent());
            if (!group.isEmpty() && tokens + t > target)
            {
                // 未超硬上限可再塞一点；否则落盘
                if (tokens + t <= max)
                {
                    group.add(leaf);
                    tokens += t;
                    continue;
                }
                out.add(makeUnit(group));
                group = new ArrayList<>();
                tokens = 0;
            }
            if (group.isEmpty() && t > max)
            {
                // 单叶超大：仍单独成单元（内容已是 LEAF）
                out.add(makeUnit(List.of(leaf)));
                continue;
            }
            group.add(leaf);
            tokens += t;
        }
        if (!group.isEmpty())
        {
            out.add(makeUnit(group));
        }
    }

    private static KbGraphTextUnit makeUnit(List<KbChunk> children)
    {
        KbGraphTextUnit u = new KbGraphTextUnit();
        StringBuilder sb = new StringBuilder();
        List<Long> ids = new ArrayList<>();
        Integer pageFrom = null;
        Integer pageTo = null;
        String firstLabel = null;
        String lastLabel = null;
        String path = children.get(0).getHeadingPath();
        String type = children.get(0).getBlockType();
        for (KbChunk c : children)
        {
            if (sb.length() > 0)
            {
                sb.append("\n\n");
            }
            // 表格 LEAF 在切片阶段已带表头，直接拼接即可
            sb.append(c.getContent());
            if (c.getChunkId() != null)
            {
                ids.add(c.getChunkId());
            }
            if (c.getSourcePageFrom() != null)
            {
                pageFrom = pageFrom == null ? c.getSourcePageFrom()
                    : Math.min(pageFrom, c.getSourcePageFrom());
            }
            if (c.getSourcePageTo() != null)
            {
                pageTo = pageTo == null ? c.getSourcePageTo()
                    : Math.max(pageTo, c.getSourcePageTo());
            }
            if (c.getSourceLabel() != null && !c.getSourceLabel().isBlank())
            {
                if (firstLabel == null)
                {
                    firstLabel = c.getSourceLabel().trim();
                }
                lastLabel = c.getSourceLabel().trim();
            }
        }
        String content = sb.toString().trim();
        u.setContent(content);
        u.setTokenCount(TokenCounter.estimate(content));
        u.setHeadingPath(path);
        u.setBlockType(type);
        u.setSourcePageFrom(pageFrom);
        u.setSourcePageTo(pageTo);
        if (firstLabel != null)
        {
            u.setSourceLabel(firstLabel.equals(lastLabel) ? firstLabel
                : firstLabel + " … " + lastLabel);
        }
        u.setLeafChunkIds(ids);
        u.setContentHash(sha256Hex(content));
        u.setParserVersion(KbConstants.PARSER_VERSION);
        u.setGraphUnitVersion(GRAPH_UNIT_VERSION);
        return u;
    }

    private static String normalizeType(String blockType)
    {
        if (blockType == null)
        {
            return "paragraph";
        }
        if (blockType.startsWith("table"))
        {
            return "table";
        }
        return blockType;
    }

    private static String sha256Hex(String text)
    {
        if (text == null)
        {
            return "";
        }
        try
        {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] dig = md.digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 16; i++)
            {
                sb.append(String.format("%02x", dig[i]));
            }
            return sb.toString();
        }
        catch (Exception e)
        {
            return Integer.toHexString(text.hashCode());
        }
    }
}
