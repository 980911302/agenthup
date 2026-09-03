package com.ruoyi.system.kb.vector;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * float[] ↔ byte[] 小端 float32 编解码。
 * <p>存 MySQL BLOB;解码后进内存索引做余弦。
 */
public final class FloatVectorCodec
{
    private FloatVectorCodec() {}

    public static byte[] encode(float[] vector)
    {
        if (vector == null)
        {
            return null;
        }
        ByteBuffer buf = ByteBuffer.allocate(vector.length * 4).order(ByteOrder.LITTLE_ENDIAN);
        for (float v : vector)
        {
            buf.putFloat(v);
        }
        return buf.array();
    }

    public static float[] decode(byte[] bytes)
    {
        if (bytes == null || bytes.length == 0)
        {
            return null;
        }
        if (bytes.length % 4 != 0)
        {
            throw new IllegalArgumentException("向量字节长度必须是 4 的倍数: " + bytes.length);
        }
        int dim = bytes.length / 4;
        float[] vector = new float[dim];
        ByteBuffer buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < dim; i++)
        {
            vector[i] = buf.getFloat();
        }
        return vector;
    }

    /**
     * 余弦相似度。零向量返回 0。
     */
    public static double cosine(float[] a, float[] b)
    {
        if (a == null || b == null || a.length == 0 || a.length != b.length)
        {
            return 0.0;
        }
        double dot = 0;
        double na = 0;
        double nb = 0;
        for (int i = 0; i < a.length; i++)
        {
            dot += (double) a[i] * b[i];
            na += (double) a[i] * a[i];
            nb += (double) b[i] * b[i];
        }
        if (na == 0 || nb == 0)
        {
            return 0.0;
        }
        return dot / (Math.sqrt(na) * Math.sqrt(nb));
    }
}
