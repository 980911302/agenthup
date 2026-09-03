package com.ruoyi.system.kb.chunker;

import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;
import com.ruoyi.system.kb.KbConstants;
import com.ruoyi.system.kb.parser.IrBlock;
import com.ruoyi.system.kb.parser.IrDoc;

/**
 * F 策略:固定 token 窗口滑动分块,兜底用。
 * <p>先把 IR 拼成带 heading 前缀的段落流,再按 token 窗口切开。
 */
@Component
public class FixedTokenChunker implements KbChunker
{
    @Override
    public String strategy()
    {
        return "F";
    }

    @Override
    public List<ChunkDraft> chunk(IrDoc irDoc, ChunkParams params)
    {
        List<Segment> segments = flatten(irDoc);
        List<ChunkDraft> result = new ArrayList<>();
        if (segments.isEmpty())
        {
            return result;
        }

        int size = Math.max(50, params.getChunkSize());
        int overlap = Math.max(0, Math.min(params.getChunkOverlap(), size / 2));
        String fp = params.fingerprint();

        StringBuilder buf = new StringBuilder();
        String currentPath = null;
        String currentType = null;
        SourceRange sourceRange = new SourceRange();

        for (Segment seg : segments)
        {
            int segTokens = TokenCounter.estimate(seg.text);
            // 路径变化且缓冲非空时先落一块,避免混章节
            if (buf.length() > 0 && !safeEquals(currentPath, seg.headingPath))
            {
                result.add(draft(result.size(), buf.toString(), currentPath, currentType,
                    TokenCounter.estimate(buf.toString()), fp, sourceRange));
                buf.setLength(0);
                sourceRange.clear();
                currentPath = null;
                currentType = null;
            }

            // 单段超长必须先按真实 token 边界切开；尤其处理“首段即超长”的情况。
            if (segTokens > size)
            {
                if (buf.length() > 0)
                {
                    result.add(draft(result.size(), buf.toString(), currentPath, currentType,
                        TokenCounter.estimate(buf.toString()), fp, sourceRange));
                    buf.setLength(0);
                    sourceRange.clear();
                }
                for (String piece : hardSplit(seg.text, size, overlap))
                {
                    result.add(draft(result.size(), piece, seg.headingPath, seg.blockType,
                        TokenCounter.estimate(piece), fp, SourceRange.of(seg)));
                }
                currentPath = null;
                currentType = null;
                continue;
            }

            if (buf.length() == 0)
            {
                buf.append(seg.text);
                currentPath = seg.headingPath;
                currentType = seg.blockType;
                sourceRange.add(seg);
                continue;
            }

            String candidate = buf + "\n\n" + seg.text;
            if (TokenCounter.estimate(candidate) <= size)
            {
                buf.append("\n\n").append(seg.text);
                currentType = mergedType(currentType, seg.blockType);
                sourceRange.add(seg);
            }
            else
            {
                // 当前缓冲满了,落盘后用严格受限的 token 尾部作为下一块 overlap。
                String previous = buf.toString();
                SourceRange previousSource = sourceRange.copy();
                String previousType = currentType;
                result.add(draft(result.size(), previous, currentPath, currentType,
                    TokenCounter.estimate(previous), fp, sourceRange));
                String overflow = fitOverlap(previous, seg.text, overlap, size);
                buf.setLength(0);
                sourceRange.clear();
                if (!overflow.isEmpty())
                {
                    buf.append(overflow).append("\n\n");
                    sourceRange.add(previousSource);
                    currentType = mergedType(previousType, seg.blockType);
                }
                else
                {
                    currentType = seg.blockType;
                }
                buf.append(seg.text);
                currentPath = seg.headingPath;
                sourceRange.add(seg);
            }
        }
        if (buf.length() > 0)
        {
            result.add(draft(result.size(), buf.toString(), currentPath, currentType,
                TokenCounter.estimate(buf.toString()), fp, sourceRange));
        }
        return result;
    }

    private List<Segment> flatten(IrDoc irDoc)
    {
        List<Segment> list = new ArrayList<>();
        if (irDoc == null || irDoc.getBlocks() == null)
        {
            return list;
        }
        for (IrBlock b : irDoc.getBlocks())
        {
            if (b.getText() == null || b.getText().isBlank())
            {
                continue;
            }
            // heading 本身不单独成块内容,只体现在后续段落的 headingPath
            if ("heading".equals(b.getBlockType()))
            {
                continue;
            }
            Segment s = new Segment();
            s.text = b.getText().trim();
            s.headingPath = joinPath(b.getHeadingPath());
            s.blockType = b.getBlockType() != null ? b.getBlockType() : "paragraph";
            s.pageNumber = b.getPageNumber();
            s.sourceLabel = b.getSourceLabel();
            list.add(s);
        }
        return list;
    }

    static String joinPath(List<String> path)
    {
        if (path == null || path.isEmpty())
        {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        for (String p : path)
        {
            if (p == null || p.isBlank())
            {
                continue;
            }
            if (sb.length() > 0)
            {
                sb.append(KbConstants.HEADING_SEP);
            }
            sb.append(p.trim());
        }
        return sb.length() == 0 ? null : sb.toString();
    }

    private ChunkDraft draft(int index, String content, String path, String type, int tokens,
                             String fp, SourceRange source)
    {
        ChunkDraft d = new ChunkDraft();
        d.setChunkIndex(index);
        d.setContent(content.trim());
        d.setHeadingPath(path);
        d.setBlockType(type);
        d.setTokenCount(tokens);
        d.setChunkerStrategy(strategy());
        d.setChunkParamsHash(fp);
        d.setChunkLevel("LEAF");
        d.setSourcePageFrom(source.pageFrom);
        d.setSourcePageTo(source.pageTo);
        d.setSourceLabel(source.label());
        return d;
    }

    private static String fitOverlap(String previous, String next, int overlapTokens, int size)
    {
        String suffix = TokenCounter.suffixWithin(previous, overlapTokens);
        if (suffix.isEmpty())
        {
            return "";
        }
        if (TokenCounter.estimate(suffix + "\n\n" + next) <= size)
        {
            return suffix;
        }

        int low = 0;
        int high = suffix.length();
        int best = suffix.length();
        while (low <= high)
        {
            int mid = low + (high - low) / 2;
            if (TokenCounter.estimate(suffix.substring(mid) + "\n\n" + next) <= size)
            {
                best = mid;
                high = mid - 1;
            }
            else
            {
                low = mid + 1;
            }
        }
        if (best < suffix.length() && Character.isLowSurrogate(suffix.charAt(best)))
        {
            best++;
        }
        return suffix.substring(Math.min(best, suffix.length())).trim();
    }

    private static List<String> hardSplit(String text, int size, int overlap)
    {
        List<String> pieces = new ArrayList<>();
        int start = 0;
        while (start < text.length())
        {
            int maxEnd = TokenCounter.maxEndWithin(text, start, size);
            int end = preferredBoundary(text, start, maxEnd);
            String piece = text.substring(start, end).trim();
            if (!piece.isEmpty())
            {
                pieces.add(piece);
            }
            if (end >= text.length())
            {
                break;
            }
            String suffix = TokenCounter.suffixWithin(text.substring(start, end), overlap);
            int next = end - suffix.length();
            start = next > start ? next : end;
        }
        return pieces;
    }

    private static int preferredBoundary(String text, int start, int maxEnd)
    {
        if (maxEnd >= text.length())
        {
            return maxEnd;
        }
        int min = start + Math.max(1, (maxEnd - start) * 2 / 3);
        int newline = text.lastIndexOf('\n', maxEnd - 1);
        if (newline >= min)
        {
            return newline + 1;
        }
        for (int i = maxEnd - 1; i >= min; i--)
        {
            char c = text.charAt(i);
            if ("。！？.!?；;".indexOf(c) >= 0 || Character.isWhitespace(c))
            {
                return i + 1;
            }
        }
        return maxEnd;
    }

    private static String mergedType(String left, String right)
    {
        return safeEquals(left, right) ? left : "mixed";
    }

    private static boolean safeEquals(String a, String b)
    {
        if (a == null)
        {
            return b == null;
        }
        return a.equals(b);
    }

    private static class Segment
    {
        String text;
        String headingPath;
        String blockType;
        Integer pageNumber;
        String sourceLabel;
    }

    private static class SourceRange
    {
        Integer pageFrom;
        Integer pageTo;
        String firstLabel;
        String lastLabel;

        static SourceRange of(Segment segment)
        {
            SourceRange range = new SourceRange();
            range.add(segment);
            return range;
        }

        void add(Segment segment)
        {
            if (segment.pageNumber != null)
            {
                pageFrom = pageFrom == null ? segment.pageNumber : Math.min(pageFrom, segment.pageNumber);
                pageTo = pageTo == null ? segment.pageNumber : Math.max(pageTo, segment.pageNumber);
            }
            if (segment.sourceLabel != null && !segment.sourceLabel.isBlank())
            {
                if (firstLabel == null) firstLabel = segment.sourceLabel.trim();
                lastLabel = segment.sourceLabel.trim();
            }
        }

        void add(SourceRange other)
        {
            if (other.pageFrom != null)
            {
                pageFrom = pageFrom == null ? other.pageFrom : Math.min(pageFrom, other.pageFrom);
                pageTo = pageTo == null ? other.pageTo : Math.max(pageTo, other.pageTo);
            }
            if (other.firstLabel != null)
            {
                if (firstLabel == null) firstLabel = other.firstLabel;
                lastLabel = other.lastLabel;
            }
        }

        SourceRange copy()
        {
            SourceRange copy = new SourceRange();
            copy.pageFrom = pageFrom;
            copy.pageTo = pageTo;
            copy.firstLabel = firstLabel;
            copy.lastLabel = lastLabel;
            return copy;
        }

        void clear()
        {
            pageFrom = null;
            pageTo = null;
            firstLabel = null;
            lastLabel = null;
        }

        String label()
        {
            if (firstLabel == null) return null;
            String label = firstLabel.equals(lastLabel) ? firstLabel : firstLabel + " … " + lastLabel;
            return label.length() <= 500 ? label : label.substring(0, 497) + "...";
        }
    }
}
