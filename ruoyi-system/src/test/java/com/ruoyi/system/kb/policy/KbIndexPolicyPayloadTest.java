package com.ruoyi.system.kb.policy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import org.junit.jupiter.api.Test;

class KbIndexPolicyPayloadTest
{
    @Test
    void roundTripAndFingerprintStable()
    {
        Map<String, Object> p = KbIndexPolicyPayload.of("emb-a", "chat-b", "P", 800, 100, "1");
        String json = KbIndexPolicyPayload.toJson(p);
        Map<String, Object> back = KbIndexPolicyPayload.fromJson(json);
        assertEquals("emb-a", KbIndexPolicyPayload.embedding(back));
        assertEquals("1", KbIndexPolicyPayload.graph(back));
        String fp1 = KbIndexPolicyPayload.fingerprint(p);
        String fp2 = KbIndexPolicyPayload.fingerprint(back);
        assertEquals(fp1, fp2);
        assertTrue(fp1.length() >= 16);
    }

    @Test
    void fingerprintChangesWithModel()
    {
        Map<String, Object> a = KbIndexPolicyPayload.of("m1", "c", "P", 800, 100, "0");
        Map<String, Object> b = KbIndexPolicyPayload.of("m2", "c", "P", 800, 100, "0");
        assertNotEquals(KbIndexPolicyPayload.fingerprint(a), KbIndexPolicyPayload.fingerprint(b));
    }
}
