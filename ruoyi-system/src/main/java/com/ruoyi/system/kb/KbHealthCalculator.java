package com.ruoyi.system.kb;

import com.ruoyi.system.domain.vo.KbWorkbenchItem;
import com.ruoyi.system.kb.access.KbAccessPolicy;

/**
 * 知识库业务健康态（普通用户可见中文状态的代码层）。
 * <p>状态码：READY / PROCESSING / PARTIAL / ISSUE / EMPTY / DISABLED
 */
public final class KbHealthCalculator
{
    private KbHealthCalculator() {}

    public static void apply(KbWorkbenchItem item)
    {
        if (item == null)
        {
            return;
        }
        int docs = nz(item.getDocCount());
        int processing = nz(item.getProcessingCount());
        int failed = nz(item.getFailedCount());
        int ready = nz(item.getReadyCount());

        // 可见范围：优先 DB visibility，兼容旧 dept
        String visCode = item.getVisibilityCode();
        if (visCode == null || visCode.isBlank())
        {
            if (item.getDeptId() != null)
            {
                item.setVisibility(KbAccessPolicy.VIS_DEPT);
            }
            else if (item.getOwnerUserId() != null || item.getCreateUserId() != null)
            {
                item.setVisibility(KbAccessPolicy.VIS_PRIVATE);
            }
            else
            {
                item.setVisibility("UNKNOWN");
            }
        }
        else
        {
            item.setVisibility(visCode.trim().toUpperCase());
        }

        if (!"0".equals(item.getStatus()))
        {
            item.setHealth("DISABLED");
            item.setHealthReason("已停用，智能体不会使用本库");
            return;
        }
        if (processing > 0)
        {
            item.setHealth("PROCESSING");
            item.setHealthReason(processing + " 篇处理中，完成后即可被检索");
            return;
        }
        if (failed > 0 && ready > 0)
        {
            item.setHealth("PARTIAL");
            item.setHealthReason(ready + " 篇可用，" + failed + " 篇失败需处理");
            return;
        }
        if (failed > 0)
        {
            item.setHealth("ISSUE");
            item.setHealthReason(failed + " 篇处理失败，库暂不可用");
            return;
        }
        if (docs == 0)
        {
            item.setHealth("EMPTY");
            item.setHealthReason("空库，可上传内容");
            return;
        }
        item.setHealth("READY");
        item.setHealthReason("可用");
    }

    /** 建议下一步动作（产品概览/工作台共用） */
    public static String suggestedAction(String health)
    {
        if (health == null)
        {
            return "open_overview";
        }
        return switch (health)
        {
            case "DISABLED" -> "open_settings";
            case "PROCESSING" -> "open_content";
            case "PARTIAL", "ISSUE" -> "open_content_failed";
            case "EMPTY" -> "add_content";
            default -> "open_overview";
        };
    }

    private static int nz(Integer v)
    {
        return v == null ? 0 : v;
    }
}
