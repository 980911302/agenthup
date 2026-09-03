package com.ruoyi.system.kb.chunker;

import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;
import com.ruoyi.system.kb.parser.IrBlock;
import com.ruoyi.system.kb.parser.IrDoc;

/**
 * P 策略:按文档原始顺序进行结构感知切片。
 * <p>标题是边界而不是内容；表格、列表、代码与普通正文互不混合。相同 headingPath
 * 再次出现时也不会跨越中间章节全局聚合，从而保证 chunkIndex 能还原原文顺序。</p>
 */
@Component
public class ParagraphChunker implements KbChunker
{
    @Override
    public String strategy()
    {
        return "P";
    }

    @Override
    public List<ChunkDraft> chunk(IrDoc irDoc, ChunkParams params)
    {
        List<ChunkDraft> result = new ArrayList<>();
        if (irDoc == null || irDoc.getBlocks() == null)
        {
            return result;
        }

        int size = Math.max(50, params.getChunkSize());
        int overlap = Math.max(0, Math.min(params.getChunkOverlap(), size / 3));
        String fingerprint = params.fingerprint();
        List<IrBlock> run = new ArrayList<>();
        String runPath = null;
        Family runFamily = null;

        for (IrBlock block : irDoc.getBlocks())
        {
            if (block == null || block.getText() == null || block.getText().isBlank())
            {
                continue;
            }
            if ("heading".equals(block.getBlockType()))
            {
                flushRun(result, run, runFamily, size, overlap, fingerprint);
                run = new ArrayList<>();
                runPath = null;
                runFamily = null;
                continue;
            }

            String path = FixedTokenChunker.joinPath(block.getHeadingPath());
            Family family = familyOf(block.getBlockType());
            if (!run.isEmpty() && (!safeEquals(runPath, path) || runFamily != family))
            {
                flushRun(result, run, runFamily, size, overlap, fingerprint);
                run = new ArrayList<>();
            }
            if (run.isEmpty())
            {
                runPath = path;
                runFamily = family;
            }
            run.add(block);
        }
        flushRun(result, run, runFamily, size, overlap, fingerprint);

        for (int i = 0; i < result.size(); i++)
        {
            result.get(i).setChunkIndex(i);
        }
        return result;
    }

    private void flushRun(List<ChunkDraft> out, List<IrBlock> run, Family family,
                          int size, int overlap, String fingerprint)
    {
        if (run == null || run.isEmpty() || family == null)
        {
            return;
        }
        switch (family)
        {
            case CODE -> chunkCode(out, run, size, overlap, fingerprint);
            case TABLE -> chunkTable(out, run, size, overlap, fingerprint);
            case LIST -> pack(out, run, size, overlap, fingerprint, Family.LIST, null);
            default -> pack(out, run, size, overlap, fingerprint, Family.TEXT, null);
        }
    }

    private void chunkCode(List<ChunkDraft> out, List<IrBlock> run, int size,
                           int overlap, String fingerprint)
    {
        for (IrBlock block : run)
        {
            addOversizeAware(out, block, format(block, Family.CODE), size, overlap,
                fingerprint, "code");
        }
    }

    private void chunkTable(List<ChunkDraft> out, List<IrBlock> run, int size,
                            int overlap, String fingerprint)
    {
        // CSV/Excel 的 header 在每个后续表格分片中重复，避免检索命中数据行却丢列语义。
        IrBlock header = null;
        for (IrBlock block : run)
        {
            if ("table_header".equals(block.getBlockType()))
            {
                header = block;
                break;
            }
        }
        pack(out, run, size, overlap, fingerprint, Family.TABLE, header);
    }

    private void pack(List<ChunkDraft> out, List<IrBlock> blocks, int size, int overlap,
                      String fingerprint, Family family, IrBlock repeatedHeader)
    {
        List<IrBlock> current = new ArrayList<>();
        StringBuilder text = new StringBuilder();
        for (IrBlock block : blocks)
        {
            String formatted = format(block, family);
            if (TokenCounter.estimate(formatted) > size)
            {
                boolean headerOnly = family == Family.TABLE && repeatedHeader != null
                    && current.size() == 1 && current.get(0) == repeatedHeader;
                if (!headerOnly)
                {
                    emit(out, current, text.toString(), family, fingerprint);
                }
                current.clear();
                text.setLength(0);
                if (family == Family.TABLE && repeatedHeader != null && block != repeatedHeader)
                {
                    addOversizeTableRow(out, repeatedHeader, block, formatted, size, overlap, fingerprint);
                }
                else
                {
                    addOversizeAware(out, block, formatted, size, overlap, fingerprint,
                        outputType(family, List.of(block)));
                }
                continue;
            }

            String candidate = append(text.toString(), formatted);
            if (text.length() > 0 && TokenCounter.estimate(candidate) > size)
            {
                emit(out, current, text.toString(), family, fingerprint);
                current = new ArrayList<>();
                text.setLength(0);
                if (family == Family.TABLE && repeatedHeader != null && block != repeatedHeader)
                {
                    String headerText = format(repeatedHeader, family);
                    text.append(headerText);
                    current.add(repeatedHeader);
                }
            }
            if (text.length() > 0)
            {
                text.append("\n\n");
            }
            text.append(formatted);
            current.add(block);
        }
        emit(out, current, text.toString(), family, fingerprint);
    }

    private void addOversizeTableRow(List<ChunkDraft> out, IrBlock header, IrBlock row,
                                     String rowText, int size, int overlap, String fingerprint)
    {
        String headerText = format(header, Family.TABLE);
        int bodySize = Math.max(20, size - TokenCounter.estimate(headerText) - 2);
        for (String piece : splitText(rowText, bodySize, overlap, false))
        {
            out.add(makeDraft(headerText + "\n\n" + piece,
                FixedTokenChunker.joinPath(row.getHeadingPath()), "table",
                List.of(header, row), fingerprint));
        }
    }

    private void addOversizeAware(List<ChunkDraft> out, IrBlock block, String text, int size,
                                  int overlap, String fingerprint, String type)
    {
        for (String piece : splitText(text, size, overlap, "code".equals(type)))
        {
            ChunkDraft draft = makeDraft(piece, FixedTokenChunker.joinPath(block.getHeadingPath()),
                type, List.of(block), fingerprint);
            out.add(draft);
        }
    }

    private void emit(List<ChunkDraft> out, List<IrBlock> blocks, String text,
                      Family family, String fingerprint)
    {
        if (text == null || text.isBlank() || blocks == null || blocks.isEmpty())
        {
            return;
        }
        out.add(makeDraft(text, FixedTokenChunker.joinPath(blocks.get(0).getHeadingPath()),
            outputType(family, blocks), blocks, fingerprint));
    }

    private ChunkDraft makeDraft(String content, String path, String type,
                                 List<IrBlock> sources, String fingerprint)
    {
        ChunkDraft draft = new ChunkDraft();
        draft.setContent(content.trim());
        draft.setHeadingPath(path);
        draft.setBlockType(type);
        draft.setTokenCount(TokenCounter.estimate(content));
        draft.setChunkerStrategy(strategy());
        draft.setChunkParamsHash(fingerprint);
        draft.setChunkLevel("LEAF");
        applySource(draft, sources);
        return draft;
    }

    private void applySource(ChunkDraft draft, List<IrBlock> sources)
    {
        Integer from = null;
        Integer to = null;
        String firstLabel = null;
        String lastLabel = null;
        for (IrBlock source : sources)
        {
            Integer page = source.getPageNumber();
            if (page != null)
            {
                from = from == null ? page : Math.min(from, page);
                to = to == null ? page : Math.max(to, page);
            }
            if (source.getSourceLabel() != null && !source.getSourceLabel().isBlank())
            {
                if (firstLabel == null)
                {
                    firstLabel = source.getSourceLabel().trim();
                }
                lastLabel = source.getSourceLabel().trim();
            }
        }
        draft.setSourcePageFrom(from);
        draft.setSourcePageTo(to);
        if (firstLabel != null)
        {
            String label = firstLabel.equals(lastLabel) ? firstLabel : firstLabel + " … " + lastLabel;
            draft.setSourceLabel(label.length() <= 500 ? label : label.substring(0, 497) + "...");
        }
    }

    private List<String> splitText(String text, int size, int overlap, boolean byLine)
    {
        if (TokenCounter.estimate(text) <= size)
        {
            return List.of(text);
        }
        String regex = byLine ? "(?<=\\n)" : "(?<=[。！？.!?；;])\\s*|(?<=\\n)";
        String[] units = text.split(regex);
        List<String> pieces = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String unit : units)
        {
            if (unit.isBlank())
            {
                continue;
            }
            if (TokenCounter.estimate(unit) > size)
            {
                if (current.length() > 0)
                {
                    pieces.add(current.toString().trim());
                    current.setLength(0);
                }
                pieces.addAll(hardSplit(unit, size, overlap));
                continue;
            }
            if (current.length() > 0 && TokenCounter.estimate(current + unit) > size)
            {
                String previous = current.toString().trim();
                pieces.add(previous);
                current.setLength(0);
                String suffix = overlapSuffix(previous, overlap);
                while (!suffix.isEmpty() && TokenCounter.estimate(suffix + unit) > size)
                {
                    suffix = suffix.substring(Math.min(suffix.length(), Math.max(1, suffix.length() / 4)));
                }
                if (!suffix.isEmpty())
                {
                    current.append(suffix);
                }
            }
            current.append(unit);
        }
        if (current.length() > 0)
        {
            pieces.add(current.toString().trim());
        }
        return pieces.stream().filter(piece -> !piece.isBlank()).toList();
    }

    private List<String> hardSplit(String text, int size, int overlap)
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

    private int preferredBoundary(String text, int start, int maxEnd)
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

    private String overlapSuffix(String text, int overlap)
    {
        return TokenCounter.suffixWithin(text, overlap);
    }

    private String format(IrBlock block, Family family)
    {
        String text = block.getText().trim();
        if (family == Family.LIST && !text.matches("^[-*+•]\\s+.*"))
        {
            return "- " + text;
        }
        return text;
    }

    private String outputType(Family family, List<IrBlock> blocks)
    {
        if (family == Family.TABLE) return "table";
        if (family == Family.LIST) return "list_item";
        if (family == Family.CODE) return "code";
        String type = blocks.get(0).getBlockType() != null ? blocks.get(0).getBlockType() : "paragraph";
        for (IrBlock block : blocks)
        {
            if (!safeEquals(type, block.getBlockType()))
            {
                return "mixed";
            }
        }
        return type;
    }

    private String append(String left, String right)
    {
        return left == null || left.isEmpty() ? right : left + "\n\n" + right;
    }

    private Family familyOf(String type)
    {
        if ("code".equals(type)) return Family.CODE;
        if ("list_item".equals(type)) return Family.LIST;
        if ("table".equals(type) || "table_header".equals(type) || "table_row".equals(type))
        {
            return Family.TABLE;
        }
        return Family.TEXT;
    }

    private static boolean safeEquals(String a, String b)
    {
        return a == null ? b == null : a.equals(b);
    }

    private enum Family { TEXT, LIST, TABLE, CODE }
}
