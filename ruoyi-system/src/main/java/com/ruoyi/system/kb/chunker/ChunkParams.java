package com.ruoyi.system.kb.chunker;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import com.ruoyi.common.utils.StringUtils;

/**
 * 分块参数。血统指纹写入 kb_chunk,改参后可判定哪些需要重建。
 */
public class ChunkParams
{
    /** F=固定 token, P=按段落/标题聚合 */
    private String strategy = "P";

    /** 目标 token 数 */
    private int chunkSize = 800;

    /** 重叠 token 数(F 策略用) */
    private int chunkOverlap = 100;

    public ChunkParams()
    {
    }

    public ChunkParams(String strategy, Integer chunkSize, Integer chunkOverlap)
    {
        if (StringUtils.isNotEmpty(strategy))
        {
            this.strategy = strategy;
        }
        if (chunkSize != null && chunkSize > 0)
        {
            this.chunkSize = chunkSize;
        }
        if (chunkOverlap != null && chunkOverlap >= 0)
        {
            this.chunkOverlap = chunkOverlap;
        }
    }

    public String getStrategy()
    {
        return strategy;
    }

    public void setStrategy(String strategy)
    {
        this.strategy = strategy;
    }

    public int getChunkSize()
    {
        return chunkSize;
    }

    public void setChunkSize(int chunkSize)
    {
        this.chunkSize = chunkSize;
    }

    public int getChunkOverlap()
    {
        return chunkOverlap;
    }

    public void setChunkOverlap(int chunkOverlap)
    {
        this.chunkOverlap = chunkOverlap;
    }

    /** 参数指纹,用于血统追踪。摄入与重建判定必须共用本方法。 */
    public String fingerprint()
    {
        return fingerprintOf(strategy, chunkSize, chunkOverlap);
    }

    /**
     * 静态指纹:重建预览侧与摄入侧共用,避免两处字面量算法漂移导致永远「需重建」。
     */
    public static String fingerprintOf(String strategy, int chunkSize, int chunkOverlap)
    {
        String s = StringUtils.isEmpty(strategy) ? "P" : strategy;
        String raw = s + "|" + chunkSize + "|" + chunkOverlap;
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
        catch (NoSuchAlgorithmException e)
        {
            return Integer.toHexString(raw.hashCode());
        }
    }
}
