package com.ruoyi.system.ai;

import java.util.ArrayList;
import java.util.List;

/**
 * 模型推荐器
 *
 * 上游 /models 接口只返回模型 ID,类型与能力按名称启发式推断,
 * 仅作"导入时的推荐值",用户可在导入表单中修改。
 *
 * @author ruoyi
 */
public final class ModelRecommender
{
    private ModelRecommender()
    {
    }

    /**
     * 推荐模型类型(CHAT/EMBEDDING/RERANK/IMAGE/VIDEO/TTS/STT/MODERATION)
     */
    public static String recommendType(String modelId)
    {
        String id = modelId == null ? "" : modelId.toLowerCase();
        if (containsAny(id, "moderation", "guard", "shield"))
        {
            return "MODERATION";
        }
        if (containsAny(id, "embedding", "embed", "bge-", "gte-", "text-embedding"))
        {
            return "EMBEDDING";
        }
        if (containsAny(id, "rerank"))
        {
            return "RERANK";
        }
        if (containsAny(id, "tts", "speech", "cosyvoice"))
        {
            return "TTS";
        }
        if (containsAny(id, "whisper", "stt", "asr", "sensevoice", "paraformer"))
        {
            return "STT";
        }
        if (containsAny(id, "video", "sora", "kling", "veo", "runway", "luma",
            "grok-imagine-video"))
        {
            return "VIDEO";
        }
        if (containsAny(id, "image", "dall", "flux", "sdxl", "stable-diffusion",
            "wanx", "wan2", "kolors", "cogview", "imagen", "seedream", "seededit"))
        {
            return "IMAGE";
        }
        return "CHAT";
    }

    /**
     * 推荐能力标签(展示用,辅助用户判断,不入库)
     */
    public static List<String> recommendCapabilities(String modelId, String modelType)
    {
        String id = modelId == null ? "" : modelId.toLowerCase();
        List<String> caps = new ArrayList<>();
        if ("CHAT".equals(modelType))
        {
            caps.add("text");
            if (containsAny(id, "-vl", "vl-", "vision", "4o", "gemini", "claude", "glm-4v", "pixtral", "qvq"))
            {
                caps.add("vision");
            }
            if (containsAny(id, "r1", "o1", "o3", "o4", "thinking", "reasoner", "qvq"))
            {
                caps.add("reasoning");
            }
        }
        else if ("IMAGE".equals(modelType))
        {
            caps.add("image");
            if (containsAny(id, "edit"))
            {
                caps.add("edit");
            }
        }
        else if ("VIDEO".equals(modelType))
        {
            caps.add("video");
        }
        else
        {
            caps.add(modelType.toLowerCase());
        }
        return caps;
    }

    private static boolean containsAny(String id, String... keywords)
    {
        for (String kw : keywords)
        {
            if (id.contains(kw))
            {
                return true;
            }
        }
        return false;
    }
}
