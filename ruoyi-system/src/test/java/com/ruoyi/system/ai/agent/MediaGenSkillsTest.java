package com.ruoyi.system.ai.agent;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MediaGenSkillsTest
{
    @Test
    void toolCopyIsShortAndPointsToSkill()
    {
        assertTrue(MediaGenSkills.imageToolDescription().contains("图片生成"));
        assertTrue(MediaGenSkills.videoToolDescription().contains("视频生成"));
        assertFalse(MediaGenSkills.imageToolDescription().contains("@image1"));
        assertFalse(MediaGenSkills.videoToolDescription().contains("firstFrameUrl"));
    }

    @Test
    void skillTemplatesKeepUsageDetails()
    {
        String image = MediaGenSkills.imageSkillTemplate();
        assertTrue(image.contains("drawImage"));
        assertTrue(image.contains("imageUrls"));
        assertTrue(image.contains("@image1"));

        String video = MediaGenSkills.videoSkillTemplate();
        assertTrue(video.contains("drawVideo"));
        assertTrue(video.contains("referenceImageUrls"));
        assertTrue(video.contains("firstFrameUrl"));
        assertTrue(video.contains("没有尾帧"));
        assertTrue(video.contains("不要传 `lastFrameUrl`"));
    }
}
