package com.ruoyi.system.ai;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VideoJobParserTest
{
    @Test
    void readsGrok2ApiCreateId()
    {
        assertEquals("job_1", VideoJobParser.jobId("{\"request_id\":\"job_1\"}"));
        assertEquals("vid_9", VideoJobParser.jobId("{\"id\":\"vid_9\"}"));
    }

    @Test
    void treatsDoneAndCompletedAsFinished()
    {
        assertEquals(VideoJobParser.Status.COMPLETED, VideoJobParser.status("{\"status\":\"done\"}"));
        assertEquals(VideoJobParser.Status.COMPLETED, VideoJobParser.status("{\"status\":\"completed\"}"));
        assertEquals(VideoJobParser.Status.FAILED, VideoJobParser.status("{\"status\":\"failed\"}"));
        assertEquals(VideoJobParser.Status.PENDING, VideoJobParser.status("{\"status\":\"pending\"}"));
        assertEquals(VideoJobParser.Status.PENDING, VideoJobParser.status("{\"status\":\"in_progress\"}"));
    }

    @Test
    void readsNestedVideoUrl()
    {
        assertEquals("https://cdn/v.mp4",
                VideoJobParser.videoUrl("{\"status\":\"done\",\"video\":{\"url\":\"https://cdn/v.mp4\"}}"));
        assertEquals("https://cdn/a.mp4", VideoJobParser.videoUrl("{\"url\":\"https://cdn/a.mp4\"}"));
        assertNull(VideoJobParser.videoUrl("{\"status\":\"pending\"}"));
    }

    @Test
    void recommendTypePrefersVideoOverImageKeywords()
    {
        assertEquals("VIDEO", ModelRecommender.recommendType("grok-imagine-video"));
        assertEquals("VIDEO", ModelRecommender.recommendType("grok-imagine-video-1.5"));
        assertEquals("VIDEO", ModelRecommender.recommendType("sora-2"));
        assertEquals("IMAGE", ModelRecommender.recommendType("grok-imagine-image"));
    }

    @Test
    void recommendCapabilitiesForVideo()
    {
        assertTrue(ModelRecommender.recommendCapabilities("grok-imagine-video", "VIDEO").contains("video"));
    }
}
