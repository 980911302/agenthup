package com.ruoyi.system.ai.run;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import com.ruoyi.system.ai.agent.ModelInputModalities;

/**
 * 一次媒体门控的判定结果:送进模型的份数 + 被拦下的明细。
 *
 * <p>媒体进模型有两条互不相干的路 —— 用户上传的附件走 {@code ChatTurnRunner}，工具产出的
 * 走 {@code AgentToolLoop.appendPromptMedia} —— 但两边的判定结论必须是同一个,否则同一份
 * 文件会因为进入方式不同而得到不同说法。原因判定收在这里,两条路共用。
 *
 * <p>这个结果此前不留任何痕迹:不落库、不进 trace、前端看不见。工具返回的
 * "loaded into your context" 是工具层的乐观陈述(工具不持有模型配置),想确认一份媒体
 * 到底进没进模型,只能去读模型的回答反推。本类的产物经 {@code media_gated} 事件送到前端。
 *
 * @param accepted 送进模型的媒体份数
 * @param rejected 被拦下的明细,每项含 modality / label / count / reason
 */
public record MediaGateReport(int accepted, List<Map<String, Object>> rejected)
{
    /** 没有任何媒体参与本轮 —— 与「有媒体但全被拦下」不是一回事,前者不该产生提示。 */
    public boolean isEmpty()
    {
        return accepted == 0 && (rejected == null || rejected.isEmpty());
    }

    /**
     * @param accepted       送达份数
     * @param rejectedCounts 被拦下的模态 → 份数(保序,让提示里的模态顺序稳定可测)
     * @param caps           当前模型的输入模态能力
     */
    public static MediaGateReport of(int accepted, Map<String, Integer> rejectedCounts,
                                     ModelInputModalities caps)
    {
        List<Map<String, Object>> detail = new ArrayList<>();
        if (rejectedCounts != null)
        {
            for (Map.Entry<String, Integer> e : rejectedCounts.entrySet())
            {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("modality", e.getKey());
                item.put("label", modalityLabel(e.getKey()));
                item.put("count", e.getValue());
                item.put("reason", reason(e.getKey(), caps));
                detail.add(item);
            }
        }
        return new MediaGateReport(accepted, detail);
    }

    /**
     * 单个模态未送达的原因。
     *
     * <p>三种原因不能混为一谈:传输层送不出去(与模型无关,换模型也没用)、模型不声明支持
     * (换模型可解)、格式超出可发送范围(换格式可解)。给用户的提示和给模型的说明都用这里。
     */
    public static String reason(String modality, ModelInputModalities caps)
    {
        if (!ModelInputModalities.transportableModality(modality))
        {
            return "该类型当前无法送入模型";
        }
        if (caps == null || !caps.supports(modality))
        {
            return "当前模型不支持这种输入";
        }
        return "格式不在可发送范围内(文档仅 PDF,音频仅 mp3/wav)";
    }

    /** 模态的中文名,给模型的说明与给前端的提示共用。 */
    public static String modalityLabel(String modality)
    {
        if (modality == null)
        {
            return "未知类型媒体";
        }
        switch (modality)
        {
            case ModelInputModalities.IMAGE: return "图片";
            case ModelInputModalities.FILE:  return "文档";
            case ModelInputModalities.VIDEO: return "视频";
            case ModelInputModalities.AUDIO: return "音频";
            default: return "未知类型媒体";
        }
    }
}
