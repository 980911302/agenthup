package com.ruoyi.system.ai;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TtsVoiceCatalogTest
{
    @Test
    void blankFallsBackToPlusOfficialFemale()
    {
        assertEquals("longanlingxin", TtsVoiceCatalog.resolve(null));
        assertEquals("longanlingxin", TtsVoiceCatalog.resolve("  "));
    }

    @Test
    void spokenGenderMapsToRealVoiceId()
    {
        assertEquals("longanlufeng", TtsVoiceCatalog.resolve("男"));
        assertEquals("longanlufeng", TtsVoiceCatalog.resolve("男声"));
        assertEquals("longanlingxin", TtsVoiceCatalog.resolve("女"));
    }

    @Test
    void officialPlusNamesResolve()
    {
        assertEquals("longanlufeng", TtsVoiceCatalog.resolve("龙安鲁风"));
        assertEquals("longanlingxin", TtsVoiceCatalog.resolve("龙安灵心"));
        assertEquals("longanhuan_v3.6", TtsVoiceCatalog.resolve("longanhuan_v3.6"));
        assertEquals("longanhuan_v3.6", TtsVoiceCatalog.resolve("元气"));
    }

    @Test
    void staleCosyVoiceIdsDoNotPassThrough()
    {
        assertEquals("longanlingxin", TtsVoiceCatalog.resolve("longanyang"));
        assertEquals("longanlingxin", TtsVoiceCatalog.resolve("龙安洋"));
        assertEquals("longanlufeng", TtsVoiceCatalog.resolve("用 longanyang 男声"));
    }

    @Test
    void clonedVoiceIdsAreKept()
    {
        assertEquals("voice-clone-abc123", TtsVoiceCatalog.resolve("voice-clone-abc123"));
    }

    @Test
    void speakToolCopyIsShortAndPointsToSkill()
    {
        String desc = TtsVoiceCatalog.toolDescription();
        assertTrue(desc.contains("语音合成"));
        assertTrue(desc.contains("loadSkill"));
        assertFalse(desc.contains("longanfengyue"));
        assertFalse(desc.contains("[excited]"));
    }

    @Test
    void skillTemplateListsWorkingVoicesAndEmotionTags()
    {
        String skill = TtsVoiceCatalog.skillTemplate();
        assertTrue(skill.contains("longanlingxin"));
        assertTrue(skill.contains("longanlufeng"));
        assertTrue(skill.contains("longanhuan_v3.6"));
        assertTrue(skill.contains("[excited]"));
        assertTrue(skill.contains("[laughing]"));
        assertFalse(skill.contains("longanyang"));
        assertFalse(skill.contains("longchuanshu_v3.6"));
    }
}
