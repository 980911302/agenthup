package com.ruoyi.system.kb.parser;

import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * 解析质量门禁。只拦截确定无法可靠检索的结果，其余问题记录为 WARN。
 */
@Component
public class IrQualityValidator
{
    private static final int MIN_PDF_VISIBLE_CHARS = 20;

    public IrQualityReport inspect(IrDoc doc)
    {
        IrQualityReport report = new IrQualityReport();
        if (doc == null || doc.getBlocks() == null)
        {
            fail(report, "解析器没有生成 IR 内容");
            return report;
        }

        int characters = 0;
        int visibleCharacters = 0;
        int replacements = 0;
        int controls = 0;
        int effectiveBlocks = 0;
        int duplicateEligible = 0;
        int duplicateBlocks = 0;
        int longestBlock = 0;
        Set<String> uniqueBlocks = new HashSet<>();

        for (IrBlock block : doc.getBlocks())
        {
            if (block == null || block.getText() == null || block.getText().isBlank())
            {
                continue;
            }
            String text = block.getText();
            effectiveBlocks++;
            characters += text.length();
            longestBlock = Math.max(longestBlock, text.length());
            for (int i = 0; i < text.length(); i++)
            {
                char c = text.charAt(i);
                if (!Character.isWhitespace(c))
                {
                    visibleCharacters++;
                }
                if (c == '\uFFFD')
                {
                    replacements++;
                }
                if (Character.isISOControl(c) && c != '\n' && c != '\r' && c != '\t')
                {
                    controls++;
                }
            }
            String normalized = text.replaceAll("\\s+", " ").trim().toLowerCase(Locale.ROOT);
            if (normalized.length() >= 20)
            {
                duplicateEligible++;
                if (!uniqueBlocks.add(normalized))
                {
                    duplicateBlocks++;
                }
            }
        }

        report.setBlockCount(effectiveBlocks);
        report.setTextLength(visibleCharacters);
        report.setReplacementCharacterRatio(ratio(replacements, characters));
        report.setControlCharacterRatio(ratio(controls, characters));
        report.setDuplicateBlockRatio(ratio(duplicateBlocks, duplicateEligible));

        if (effectiveBlocks == 0 || visibleCharacters == 0)
        {
            fail(report, "解析后没有可检索文本");
        }
        if ("pdf".equalsIgnoreCase(doc.getSourceType()) && visibleCharacters < MIN_PDF_VISIBLE_CHARS)
        {
            report.setScannedSuspected(true);
            fail(report, "PDF 文本过少，疑似扫描件或图片型 PDF，需要 OCR");
        }
        if (report.getReplacementCharacterRatio() > 0.01)
        {
            fail(report, "替换字符比例过高，疑似编码错误或乱码");
        }
        else if (report.getReplacementCharacterRatio() > 0.001)
        {
            warn(report, "检测到少量替换字符，请抽查解析结果");
        }
        if (report.getControlCharacterRatio() > 0.005)
        {
            fail(report, "异常控制字符比例过高，疑似二进制内容被当作文本");
        }
        if (duplicateEligible >= 5 && report.getDuplicateBlockRatio() >= 0.8)
        {
            fail(report, "重复文本块比例过高，疑似页眉页脚或解析器重复抽取");
        }
        else if (duplicateEligible >= 5 && report.getDuplicateBlockRatio() >= 0.35)
        {
            warn(report, "重复文本块较多，建议检查页眉页脚或重复区域");
        }
        if (longestBlock > 100_000)
        {
            warn(report, "存在超大文本块，将在切片阶段按语义边界拆分");
        }
        return report;
    }

    private static double ratio(int numerator, int denominator)
    {
        return denominator == 0 ? 0D : numerator * 1D / denominator;
    }

    private static void warn(IrQualityReport report, String message)
    {
        if (IrQualityReport.PASS.equals(report.getStatus()))
        {
            report.setStatus(IrQualityReport.WARN);
        }
        report.getWarnings().add(message);
    }

    private static void fail(IrQualityReport report, String message)
    {
        report.setStatus(IrQualityReport.FAIL);
        report.getWarnings().add(message);
    }
}
