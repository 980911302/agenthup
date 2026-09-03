package com.ruoyi.system.kb.parser;

import java.util.ArrayList;
import java.util.List;

/**
 * 统一 IR 的质量检测结果。报告会随 IR JSON 保存，便于定位解析失败和后续调参。
 */
public class IrQualityReport
{
    public static final String PASS = "PASS";
    public static final String WARN = "WARN";
    public static final String FAIL = "FAIL";

    private String status = PASS;
    private int blockCount;
    private int textLength;
    private double replacementCharacterRatio;
    private double controlCharacterRatio;
    private double duplicateBlockRatio;
    private boolean scannedSuspected;
    private List<String> warnings = new ArrayList<>();

    public boolean isAccepted()
    {
        return !FAIL.equals(status);
    }

    public String summary()
    {
        if (warnings == null || warnings.isEmpty())
        {
            return status;
        }
        return status + ": " + String.join("；", warnings);
    }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public int getBlockCount() { return blockCount; }
    public void setBlockCount(int blockCount) { this.blockCount = blockCount; }
    public int getTextLength() { return textLength; }
    public void setTextLength(int textLength) { this.textLength = textLength; }
    public double getReplacementCharacterRatio() { return replacementCharacterRatio; }
    public void setReplacementCharacterRatio(double ratio) { this.replacementCharacterRatio = ratio; }
    public double getControlCharacterRatio() { return controlCharacterRatio; }
    public void setControlCharacterRatio(double ratio) { this.controlCharacterRatio = ratio; }
    public double getDuplicateBlockRatio() { return duplicateBlockRatio; }
    public void setDuplicateBlockRatio(double ratio) { this.duplicateBlockRatio = ratio; }
    public boolean isScannedSuspected() { return scannedSuspected; }
    public void setScannedSuspected(boolean scannedSuspected) { this.scannedSuspected = scannedSuspected; }
    public List<String> getWarnings() { return warnings; }
    public void setWarnings(List<String> warnings) { this.warnings = warnings != null ? warnings : new ArrayList<>(); }
}
