package com.ruoyi.system.kb.chunker;

import java.util.List;
import com.ruoyi.system.kb.parser.IrDoc;

/**
 * 分块器:IrDoc → 有序 chunk 草稿列表。
 */
public interface KbChunker
{
    /** 策略代码:F 或 P */
    String strategy();

    List<ChunkDraft> chunk(IrDoc irDoc, ChunkParams params);
}
