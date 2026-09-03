package com.ruoyi.system.kb.vector;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class FloatVectorCodecTest
{
    @Test
    void roundTrip()
    {
        float[] original = new float[] { 0.1f, -0.5f, 1.0f, 3.14159f, 0f };
        byte[] bytes = FloatVectorCodec.encode(original);
        assertNotNull(bytes);
        assertEquals(original.length * 4, bytes.length);
        float[] decoded = FloatVectorCodec.decode(bytes);
        assertArrayEquals(original, decoded, 1e-6f);
    }

    @Test
    void cosineKnownVectors()
    {
        float[] a = new float[] { 1f, 0f, 0f };
        float[] b = new float[] { 1f, 0f, 0f };
        float[] c = new float[] { 0f, 1f, 0f };
        float[] d = new float[] { -1f, 0f, 0f };

        assertEquals(1.0, FloatVectorCodec.cosine(a, b), 1e-6);
        assertEquals(0.0, FloatVectorCodec.cosine(a, c), 1e-6);
        assertEquals(-1.0, FloatVectorCodec.cosine(a, d), 1e-6);
        assertEquals(0.0, FloatVectorCodec.cosine(a, new float[] { 0f, 0f, 0f }), 1e-6);
        assertEquals(0.0, FloatVectorCodec.cosine(null, a), 1e-6);
    }

    @Test
    void decodeRejectsBadLength()
    {
        assertThrows(IllegalArgumentException.class,
            () -> FloatVectorCodec.decode(new byte[] { 1, 2, 3 }));
    }
}
