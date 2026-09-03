package com.ruoyi.system.kb.graph.extract;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import com.ruoyi.system.kb.graph.KbGraphConstants;

/**
 * 知识库级图抽取配置（KB-GR-06）。
 * <p>指纹进入缓存 key：prompt/profile 变更只使受影响结果过期。
 */
public class GraphExtractionProfile
{
    private String language = "zh";
    private String domain = "";
    private String promptVersion = "p1";
    private List<String> allowedEntityTypes = new ArrayList<>();
    private List<String> allowedRelationTypes = new ArrayList<>();
    private int maxEntitiesPerUnit = KbGraphConstants.MAX_ENTITIES_PER_CHUNK;
    private int maxRelationsPerUnit = KbGraphConstants.MAX_RELATIONS_PER_CHUNK;
    /** 默认 0：关闭 gleaning */
    private int maxGleanings = 0;
    private int maxRetries = 2;
    private long retryBaseMs = 200L;
    /** 文本至少这么长才考虑 gleaning（字符） */
    private int gleaningMinChars = 200;

    public static GraphExtractionProfile defaults()
    {
        return new GraphExtractionProfile();
    }

    public String fingerprint()
    {
        String raw = String.join("|",
            nullToEmpty(language),
            nullToEmpty(domain),
            nullToEmpty(promptVersion),
            String.valueOf(maxEntitiesPerUnit),
            String.valueOf(maxRelationsPerUnit),
            String.valueOf(maxGleanings),
            String.join(",", allowedEntityTypes),
            String.join(",", allowedRelationTypes));
        try
        {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] dig = md.digest(raw.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 16; i++)
            {
                sb.append(String.format("%02x", dig[i]));
            }
            return sb.toString();
        }
        catch (Exception e)
        {
            return Integer.toHexString(raw.hashCode());
        }
    }

    public String buildSystemPrompt()
    {
        // 有显式类型白名单时，用中文清单覆盖默认类型说明
        String typeGuidance = ExtractPrompt.DEFAULT_ENTITY_TYPES_ZH;
        if (!allowedEntityTypes.isEmpty())
        {
            typeGuidance = "请优先使用下列实体类型（写在 type 字段）：\n- "
                + String.join("\n- ", allowedEntityTypes)
                + "\n若确实无法归类可用「其他」。";
        }
        StringBuilder extra = new StringBuilder();
        if (domain != null && !domain.isBlank())
        {
            extra.append("\n## 业务领域\n本知识库侧重：").append(domain.trim())
                .append("。抽取时优先保留与该领域相关的实体与关系。");
        }
        if (!allowedRelationTypes.isEmpty())
        {
            extra.append("\n## 关系关键词偏好\nkeywords 优先贴近：")
                .append(String.join("、", allowedRelationTypes));
        }
        if (language != null && !language.isBlank() && !"zh".equalsIgnoreCase(language.trim()))
        {
            extra.append("\n## 语言\n输出语言代码：").append(language.trim())
                .append("（仍优先保证名称可被中文用户理解）。");
        }
        return ExtractPrompt.systemPrompt(maxEntitiesPerUnit, maxRelationsPerUnit, typeGuidance)
            + (extra.length() > 0 ? extra : "")
            + "\n(promptVersion=" + promptVersion + ")";
    }

    private static String nullToEmpty(String s)
    {
        return s == null ? "" : s;
    }

    public String getLanguage()
    {
        return language;
    }

    public void setLanguage(String language)
    {
        this.language = language;
    }

    public String getDomain()
    {
        return domain;
    }

    public void setDomain(String domain)
    {
        this.domain = domain;
    }

    public String getPromptVersion()
    {
        return promptVersion;
    }

    public void setPromptVersion(String promptVersion)
    {
        this.promptVersion = promptVersion;
    }

    public List<String> getAllowedEntityTypes()
    {
        return allowedEntityTypes;
    }

    public void setAllowedEntityTypes(List<String> allowedEntityTypes)
    {
        this.allowedEntityTypes = allowedEntityTypes != null ? allowedEntityTypes : new ArrayList<>();
    }

    public List<String> getAllowedRelationTypes()
    {
        return allowedRelationTypes;
    }

    public void setAllowedRelationTypes(List<String> allowedRelationTypes)
    {
        this.allowedRelationTypes = allowedRelationTypes != null ? allowedRelationTypes : new ArrayList<>();
    }

    public int getMaxEntitiesPerUnit()
    {
        return maxEntitiesPerUnit;
    }

    public void setMaxEntitiesPerUnit(int maxEntitiesPerUnit)
    {
        this.maxEntitiesPerUnit = Math.max(1, maxEntitiesPerUnit);
    }

    public int getMaxRelationsPerUnit()
    {
        return maxRelationsPerUnit;
    }

    public void setMaxRelationsPerUnit(int maxRelationsPerUnit)
    {
        this.maxRelationsPerUnit = Math.max(1, maxRelationsPerUnit);
    }

    public int getMaxGleanings()
    {
        return maxGleanings;
    }

    public void setMaxGleanings(int maxGleanings)
    {
        this.maxGleanings = Math.max(0, maxGleanings);
    }

    public int getMaxRetries()
    {
        return maxRetries;
    }

    public void setMaxRetries(int maxRetries)
    {
        this.maxRetries = Math.max(0, maxRetries);
    }

    public long getRetryBaseMs()
    {
        return retryBaseMs;
    }

    public void setRetryBaseMs(long retryBaseMs)
    {
        this.retryBaseMs = Math.max(50L, retryBaseMs);
    }

    public int getGleaningMinChars()
    {
        return gleaningMinChars;
    }

    public void setGleaningMinChars(int gleaningMinChars)
    {
        this.gleaningMinChars = Math.max(0, gleaningMinChars);
    }
}
