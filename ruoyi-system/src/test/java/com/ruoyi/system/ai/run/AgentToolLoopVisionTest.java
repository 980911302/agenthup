package com.ruoyi.system.ai.run;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.content.Media;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.util.MimeType;
import org.springframework.util.MimeTypeUtils;
import com.ruoyi.system.ai.agent.ModelInputModalities;
import com.ruoyi.system.tool.PromptMediaBuffer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 工具产出媒体的门控。类名保留 Vision 是历史原因,覆盖范围已扩到四种输入模态。
 */
class AgentToolLoopVisionTest
{
    private static final ModelInputModalities NONE = ModelInputModalities.parse("");
    private static final ModelInputModalities IMAGE = ModelInputModalities.parse("image");
    private static final ModelInputModalities IMAGE_FILE = ModelInputModalities.parse("image,file");

    @AfterEach
    void drainBuffer()
    {
        PromptMediaBuffer.drain();
    }

    @Test
    void visionOff_appendsNoticeWithoutMedia()
    {
        List<Message> out = AgentToolLoop.appendPromptMedia(List.of(), List.of(png()), NONE);
        assertEquals(1, out.size());
        UserMessage msg = (UserMessage) out.get(0);
        assertTrue(msg.getText().contains("图片"));
        assertTrue(msg.getText().contains("当前模型不支持这种输入"));
        // 工具照常返回「Image attached: xxx」,那句话对前端是真的但模型没收到图。
        // 提示必须点名并否定它 —— 只含糊说「已跳过」时,模型面对两条冲突信息
        // 往往选择相信工具返回。
        assertTrue(msg.getText().contains("Image attached"),
                "提示必须直接否定工具返回里的原话,否则模型会相信工具那句");
        assertTrue(msg.getText().contains("未送达"));
        assertTrue(msg.getMedia() == null || msg.getMedia().isEmpty());
    }

    @Test
    void visionOn_keepsMedia()
    {
        List<Message> out = AgentToolLoop.appendPromptMedia(List.of(), List.of(png()), IMAGE);
        UserMessage msg = (UserMessage) out.get(0);
        assertEquals(1, msg.getMedia().size());
        assertTrue(msg.getText().contains("请查看"));
    }

    @Test
    void visionOff_stillDrainsPromptMediaBuffer()
    {
        PromptMediaBuffer.addAll(List.of(png()));
        List<Media> drained = PromptMediaBuffer.drain();
        assertEquals(1, drained.size());
        assertTrue(PromptMediaBuffer.drain().isEmpty());
        List<Message> out = AgentToolLoop.appendPromptMedia(List.of(), drained, NONE);
        assertFalse(((UserMessage) out.get(0)).getText().isBlank());
        assertTrue(PromptMediaBuffer.drain().isEmpty());
    }

    /** 支持文档的模型:PDF 该跟图片一样进 media,而不是被当成"非图片"丢掉。 */
    @Test
    void pdfEntersMediaWhenFileSupported()
    {
        List<Message> out = AgentToolLoop.appendPromptMedia(List.of(), List.of(pdf()), IMAGE_FILE);
        UserMessage msg = (UserMessage) out.get(0);
        assertEquals(1, msg.getMedia().size());
    }

    /** 只支持图片的模型收到 PDF:图片照进,PDF 单独出说明 —— 一刀切会连图片一起丢。 */
    @Test
    void mixedBatchSplitsByModality()
    {
        List<Message> out = AgentToolLoop.appendPromptMedia(List.of(), List.of(png(), pdf()), IMAGE);
        assertEquals(2, out.size(), "一条带图的消息 + 一条说明");
        UserMessage kept = (UserMessage) out.get(0);
        assertEquals(1, kept.getMedia().size());
        UserMessage notice = (UserMessage) out.get(1);
        assertTrue(notice.getText().contains("文档"));
        assertFalse(notice.getText().contains("1 份图片"), "图片已送达,不该出现在未送达清单里");
    }

    /**
     * 视频即使被模型声明支持也发不出去 —— Spring AI 会把它兜底成 image_url。
     * 这条锁住"宁可不发也不发错"的取舍。
     */
    @Test
    void videoRejectedEvenWhenDeclaredSupported()
    {
        ModelInputModalities all = ModelInputModalities.parse("image,file,video,audio");
        List<Message> out = AgentToolLoop.appendPromptMedia(List.of(), List.of(mp4()), all);
        assertEquals(1, out.size());
        UserMessage msg = (UserMessage) out.get(0);
        assertTrue(msg.getMedia() == null || msg.getMedia().isEmpty());
        assertTrue(msg.getText().contains("视频"));
        assertTrue(msg.getText().contains("无法送入模型"));
    }

    /** 声明支持音频,但 ogg 不在 Spring AI 的白名单里,同样不能发。 */
    @Test
    void unsupportedAudioFormatRejected()
    {
        ModelInputModalities audio = ModelInputModalities.parse("audio");
        List<Message> out = AgentToolLoop.appendPromptMedia(List.of(), List.of(media("audio/ogg")), audio);
        UserMessage msg = (UserMessage) out.get(0);
        assertTrue(msg.getMedia() == null || msg.getMedia().isEmpty());
        assertTrue(msg.getText().contains("音频"));
    }

    @Test
    void mp3AcceptedWhenAudioSupported()
    {
        ModelInputModalities audio = ModelInputModalities.parse("audio");
        List<Message> out = AgentToolLoop.appendPromptMedia(List.of(), List.of(media("audio/mp3")), audio);
        assertEquals(1, ((UserMessage) out.get(0)).getMedia().size());
    }

    @Test
    void nullModalitiesDegradesToNothingSent()
    {
        List<Message> out = AgentToolLoop.appendPromptMedia(List.of(), List.of(png()), null);
        UserMessage msg = (UserMessage) out.get(0);
        assertTrue(msg.getMedia() == null || msg.getMedia().isEmpty());
    }

    // ---- 门控结果回报(前端据此显示「已送入模型」/「被拦下」)----

    @Test
    void 门控回报送入份数()
    {
        List<MediaGateReport> got = new ArrayList<>();
        AgentToolLoop.appendPromptMedia(List.of(), List.of(png(), png()), IMAGE, got::add);

        assertEquals(1, got.size());
        assertEquals(2, got.get(0).accepted());
        assertTrue(got.get(0).rejected().isEmpty());
    }

    @Test
    void 门控回报拦下的模态与原因()
    {
        List<MediaGateReport> got = new ArrayList<>();
        // 模型只支持图片:PDF 因模型不支持被拦,视频因传输层送不出去被拦
        AgentToolLoop.appendPromptMedia(List.of(), List.of(png(), pdf(), mp4()), IMAGE, got::add);

        MediaGateReport out = got.get(0);
        assertEquals(1, out.accepted(), "只有图片进得去");
        assertEquals(2, out.rejected().size());

        Map<String, Map<String, Object>> byModality = new LinkedHashMap<>();
        for (Map<String, Object> item : out.rejected())
        {
            byModality.put((String) item.get("modality"), item);
        }
        assertEquals("文档", byModality.get("file").get("label"));
        assertEquals("当前模型不支持这种输入", byModality.get("file").get("reason"));
        assertEquals("视频", byModality.get("video").get("label"));
        assertEquals("该类型当前无法送入模型", byModality.get("video").get("reason"),
                "视频是传输层送不出去,与模型支不支持无关");
    }

    @Test
    void 拒绝原因与给模型的说明口径一致()
    {
        List<MediaGateReport> got = new ArrayList<>();
        List<Message> out = AgentToolLoop.appendPromptMedia(
                List.of(), List.of(pdf()), IMAGE, got::add);

        // 同一个判定不能给用户和给模型两套说法
        String noticeToModel = ((UserMessage) out.get(out.size() - 1)).getText();
        String reasonToUser = (String) got.get(0).rejected().get(0).get("reason");
        assertTrue(noticeToModel.contains(reasonToUser),
                "给前端的原因必须与给模型的说明同源: " + reasonToUser);
    }

    @Test
    void 无媒体时不回报()
    {
        List<MediaGateReport> got = new ArrayList<>();
        AgentToolLoop.appendPromptMedia(List.of(), List.of(), IMAGE, got::add);
        assertTrue(got.isEmpty(), "没有媒体就没有门控判定,不该产生事件");
    }

    @Test
    void 不传回调时照常工作()
    {
        List<Message> out = AgentToolLoop.appendPromptMedia(List.of(), List.of(png()), IMAGE, null);
        assertEquals(1, ((UserMessage) out.get(0)).getMedia().size());
    }

    private static Media png()
    {
        return new Media(MimeTypeUtils.IMAGE_PNG, new ByteArrayResource(new byte[] { 1, 2, 3 }));
    }

    private static Media pdf()
    {
        return media("application/pdf");
    }

    private static Media mp4()
    {
        return media("video/mp4");
    }

    private static Media media(String mime)
    {
        MimeType type = MimeTypeUtils.parseMimeType(mime);
        return Media.builder()
                .mimeType(type)
                .data(new ByteArrayResource(new byte[] { 1, 2, 3 }))
                .name("sample")
                .build();
    }
}
