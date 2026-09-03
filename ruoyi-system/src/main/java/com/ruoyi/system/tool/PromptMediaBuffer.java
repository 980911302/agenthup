package com.ruoyi.system.tool;

import java.util.ArrayList;
import java.util.List;
import org.springframework.ai.content.Media;

/**
 * 当前工具批次产出的、要立刻喂给下一轮 LLM 的图片。
 * <p>与 {@link ToolCallIdMatcher} 一样走调用线程,{@link AgentToolLoop} 在批次结束后 drain。
 */
public final class PromptMediaBuffer
{
    private static final ThreadLocal<List<Media>> HELD = ThreadLocal.withInitial(ArrayList::new);

    private PromptMediaBuffer() { }

    public static void addAll(List<Media> medias)
    {
        if (medias == null || medias.isEmpty())
        {
            return;
        }
        HELD.get().addAll(medias);
    }

    public static List<Media> drain()
    {
        List<Media> held = HELD.get();
        if (held.isEmpty())
        {
            return List.of();
        }
        List<Media> copy = List.copyOf(held);
        held.clear();
        return copy;
    }
}
