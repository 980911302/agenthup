package com.ruoyi.system.kb.parser;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * CSV/TSV 解析。状态机支持引号、转义双引号与单元格内换行。
 */
@Component
public class DelimitedTextParser implements KbParser
{
    private static final Set<String> EXTS = Set.of("csv", "tsv");

    @Override
    public boolean supports(String extension)
    {
        return extension != null && EXTS.contains(extension.toLowerCase());
    }

    @Override
    public IrDoc parse(File file, String fileName) throws Exception
    {
        String ext = ParserSupport.extensionOf(fileName);
        char delimiter = "tsv".equals(ext) ? '\t' : ',';
        String source = Files.readString(file.toPath(), StandardCharsets.UTF_8);
        List<List<String>> records = parseRecords(source, delimiter);
        IrDoc doc = ParserSupport.newDocument(fileName, ext);
        if (records.isEmpty())
        {
            return doc;
        }

        List<String> headers = normalizeHeaders(records.get(0));
        int pos = 0;
        ParserSupport.addBlock(doc, pos++, String.join(" | ", headers),
            "table_header", List.of(), null, fileName);
        for (int i = 1; i < records.size(); i++)
        {
            String text = rowAsText(headers, records.get(i));
            if (ParserSupport.addBlock(doc, pos, text, "table_row", List.of(), null, fileName) != null)
            {
                pos++;
            }
        }
        return doc;
    }

    static List<List<String>> parseRecords(String source, char delimiter)
    {
        List<List<String>> records = new ArrayList<>();
        List<String> row = new ArrayList<>();
        StringBuilder cell = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < source.length(); i++)
        {
            char c = source.charAt(i);
            if (c == '"')
            {
                if (quoted && i + 1 < source.length() && source.charAt(i + 1) == '"')
                {
                    cell.append('"');
                    i++;
                }
                else
                {
                    quoted = !quoted;
                }
            }
            else if (c == delimiter && !quoted)
            {
                row.add(ParserSupport.normalize(cell.toString()));
                cell.setLength(0);
            }
            else if ((c == '\n' || c == '\r') && !quoted)
            {
                if (c == '\r' && i + 1 < source.length() && source.charAt(i + 1) == '\n')
                {
                    i++;
                }
                row.add(ParserSupport.normalize(cell.toString()));
                cell.setLength(0);
                if (row.stream().anyMatch(s -> !s.isEmpty()))
                {
                    records.add(row);
                }
                row = new ArrayList<>();
            }
            else
            {
                cell.append(c);
            }
        }
        row.add(ParserSupport.normalize(cell.toString()));
        if (row.stream().anyMatch(s -> !s.isEmpty()))
        {
            records.add(row);
        }
        return records;
    }

    private static List<String> normalizeHeaders(List<String> values)
    {
        List<String> headers = new ArrayList<>(values.size());
        for (int i = 0; i < values.size(); i++)
        {
            String value = values.get(i);
            headers.add(value == null || value.isEmpty() ? "列" + (i + 1) : value);
        }
        return headers;
    }

    private static String rowAsText(List<String> headers, List<String> values)
    {
        List<String> cells = new ArrayList<>();
        int count = Math.max(headers.size(), values.size());
        for (int i = 0; i < count; i++)
        {
            String value = i < values.size() ? ParserSupport.normalize(values.get(i)) : "";
            if (value.isEmpty())
            {
                continue;
            }
            String header = i < headers.size() ? headers.get(i) : "列" + (i + 1);
            cells.add(header + ": " + value);
        }
        return String.join(" | ", cells);
    }
}
