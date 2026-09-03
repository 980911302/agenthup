package com.ruoyi.system.tool;

import java.util.List;
import org.springframework.ai.content.Media;

/**
 * 工具本次产出需要立刻进入下一轮模型上下文的图片。
 * <p>只回文本路径的话模型看不见图,{@link AttachmentAware} 只服务前端/落库。
 */
public interface PromptMediaAware
{
    /** 最近一次 call() 要喂给模型的图片;无则 null 或空。 */
    List<Media> lastPromptMedia();
}
