package com.ruoyi.system.kb.chunker;

import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import com.ruoyi.system.kb.parser.IrDoc;

/**
 * 按策略代码路由分块器,默认 P。
 */
@Component
public class KbChunkerRegistry
{
    @Autowired
    private List<KbChunker> chunkers;

    @Autowired
    private ParagraphChunker paragraphChunker;

    public List<ChunkDraft> chunk(IrDoc irDoc, ChunkParams params)
    {
        String strategy = params.getStrategy() != null ? params.getStrategy().toUpperCase() : "P";
        for (KbChunker c : chunkers)
        {
            if (strategy.equalsIgnoreCase(c.strategy()))
            {
                return c.chunk(irDoc, params);
            }
        }
        return paragraphChunker.chunk(irDoc, params);
    }
}
