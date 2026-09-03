package com.ruoyi.system.kb.graph.merge;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.UUID;
import com.ruoyi.common.utils.StringUtils;
import com.ruoyi.system.kb.graph.GraphEntity;

/**
 * 实体候选键 / 稳定 entityKey。
 * <p>candidateKey 仅用于召回；entityKey 含 type 维度，同名异义（不同类型）不合并。
 * 默认「不合并优于错误合并」：不做 LLM 消歧（留给后续人工/模型开关）。
 */
public final class EntityCanonicalizer
{
    private EntityCanonicalizer()
    {
    }

    public static final class Identity
    {
        public final String displayName;
        public final String normalizedName;
        public final String normalizedType;
        public final String candidateKey;
        public final String entityKey;
        public final String senseDiscriminator;

        Identity(String displayName, String normalizedName, String normalizedType,
                 String candidateKey, String entityKey, String senseDiscriminator)
        {
            this.displayName = displayName;
            this.normalizedName = normalizedName;
            this.normalizedType = normalizedType;
            this.candidateKey = candidateKey;
            this.entityKey = entityKey;
            this.senseDiscriminator = senseDiscriminator;
        }
    }

    public static Identity identityOf(Long kbId, GraphEntity e)
    {
        String rawName = e != null ? e.getName() : null;
        String rawType = e != null ? e.getType() : null;
        return identityOf(kbId, rawName, rawType, null);
    }

    /**
     * @param sense 显式义项；null/空表示默认义项
     */
    public static Identity identityOf(Long kbId, String rawName, String rawType, String sense)
    {
        String display = EntityAliasTable.resolveDisplayName(rawName);
        String normName = EntityAliasTable.resolveNormalizedKey(rawName);
        String normType = EntityNormalizer.normalizeType(rawType);
        String senseDisc = StringUtils.isEmpty(sense) ? "" : sense.trim().toLowerCase(Locale.ROOT);
        // candidateKey：不含 sense，用于「可能是同一实体」的召回
        String candidateKey = hashHex((kbId == null ? 0 : kbId) + "|" + normName + "|" + normType);
        // entityKey：含 sense，同名异义可共存
        String entityKey = (kbId == null ? "0" : kbId) + "|" + normName + "|" + normType + "|" + senseDisc;
        return new Identity(display, normName, normType, candidateKey, entityKey, senseDisc);
    }

    /** 稳定 UUID（确定性，非随机） */
    public static String stableEntityId(String entityKey)
    {
        return UUID.nameUUIDFromBytes(("kb-entity:" + entityKey).getBytes(StandardCharsets.UTF_8))
            .toString();
    }

    public static void applyTo(GraphEntity e, Long kbId)
    {
        if (e == null)
        {
            return;
        }
        Identity id = identityOf(kbId, e);
        e.setName(id.displayName);
        e.setCanonicalName(id.displayName);
        e.setCandidateKey(id.candidateKey);
        e.setEntityKey(id.entityKey);
        if (StringUtils.isEmpty(e.getType()) && StringUtils.isNotEmpty(id.normalizedType))
        {
            e.setType(id.normalizedType);
        }
    }

    private static String hashHex(String raw)
    {
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
        catch (Exception ex)
        {
            return Integer.toHexString(raw.hashCode());
        }
    }
}
