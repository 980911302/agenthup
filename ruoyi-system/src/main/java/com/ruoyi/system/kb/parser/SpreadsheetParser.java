package com.ruoyi.system.kb.parser;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.FormulaEvaluator;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.springframework.stereotype.Component;

/**
 * Excel XLS/XLSX 解析。按工作表保留顺序,首个非空行作为表头,后续行输出可检索的键值文本。
 */
@Component
public class SpreadsheetParser implements KbParser
{
    private static final Set<String> EXTS = Set.of("xls", "xlsx");

    @Override
    public boolean supports(String extension)
    {
        return extension != null && EXTS.contains(extension.toLowerCase());
    }

    @Override
    public IrDoc parse(File file, String fileName) throws Exception
    {
        String ext = ParserSupport.extensionOf(fileName);
        IrDoc doc = ParserSupport.newDocument(fileName, ext);
        int pos = 0;
        try (Workbook workbook = WorkbookFactory.create(file))
        {
            DataFormatter formatter = new DataFormatter();
            FormulaEvaluator evaluator = workbook.getCreationHelper().createFormulaEvaluator();
            for (int sheetIndex = 0; sheetIndex < workbook.getNumberOfSheets(); sheetIndex++)
            {
                Sheet sheet = workbook.getSheetAt(sheetIndex);
                if (sheet == null)
                {
                    continue;
                }
                String sheetName = sheet.getSheetName();
                List<String> path = List.of("工作表: " + sheetName);
                ParserSupport.addHeading(doc, pos++, sheetName, 1, path, null, sheetName);

                List<String> headers = null;
                for (Row row : sheet)
                {
                    List<String> values = rowValues(row, formatter, evaluator);
                    if (isEmpty(values))
                    {
                        continue;
                    }
                    if (headers == null)
                    {
                        headers = normalizeHeaders(values);
                        ParserSupport.addBlock(doc, pos++, String.join(" | ", headers),
                            "table_header", path, null, sheetName);
                        continue;
                    }
                    String text = rowAsText(headers, values);
                    if (ParserSupport.addBlock(doc, pos, text, "table_row", path, null, sheetName) != null)
                    {
                        pos++;
                    }
                }
            }
        }
        return doc;
    }

    private static List<String> rowValues(Row row, DataFormatter formatter, FormulaEvaluator evaluator)
    {
        int last = Math.max(0, row.getLastCellNum());
        List<String> values = new ArrayList<>(last);
        for (int i = 0; i < last; i++)
        {
            Cell cell = row.getCell(i, Row.MissingCellPolicy.RETURN_BLANK_AS_NULL);
            String value = cell != null ? formatter.formatCellValue(cell, evaluator) : "";
            values.add(ParserSupport.normalize(value));
        }
        int end = values.size();
        while (end > 0 && values.get(end - 1).isEmpty())
        {
            end--;
        }
        return new ArrayList<>(values.subList(0, end));
    }

    private static boolean isEmpty(List<String> values)
    {
        return values == null || values.stream().allMatch(String::isEmpty);
    }

    private static List<String> normalizeHeaders(List<String> values)
    {
        List<String> headers = new ArrayList<>(values.size());
        for (int i = 0; i < values.size(); i++)
        {
            String value = values.get(i);
            headers.add(value.isEmpty() ? "列" + (i + 1) : value);
        }
        return headers;
    }

    private static String rowAsText(List<String> headers, List<String> values)
    {
        List<String> cells = new ArrayList<>();
        int count = Math.max(headers.size(), values.size());
        for (int i = 0; i < count; i++)
        {
            String value = i < values.size() ? values.get(i) : "";
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
