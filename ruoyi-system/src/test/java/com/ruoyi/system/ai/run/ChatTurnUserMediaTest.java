package com.ruoyi.system.ai.run;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.ai.content.Media;
import com.ruoyi.system.ai.agent.ModelInputModalities;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 用户上传附件进模型的门控。覆盖四种输入模态,不再只区分「图片 / 非图片」。
 */
class ChatTurnUserMediaTest
{
    private static final ModelInputModalities NONE = ModelInputModalities.parse("");
    private static final ModelInputModalities IMAGE = ModelInputModalities.parse("image");
    private static final ModelInputModalities IMAGE_FILE = ModelInputModalities.parse("image,file");

    private final ChatTurnRunner runner = new ChatTurnRunner();

    // ---- 门控结果回报(前端据此显示上传的附件模型收到没有)----

    @Test
    void 门控回报上传图片已送达()
    {
        ChatTurnAttachment img = new ChatTurnAttachment("a.png", "uploads/a.png", "image/png", 12L);
        List<MediaGateReport> got = new ArrayList<>();
        runner.composeUserText("看看", List.of(img), IMAGE, got::add);

        assertEquals(1, got.size());
        assertEquals(1, got.get(0).accepted());
        assertTrue(got.get(0).rejected().isEmpty());
    }

    @Test
    void 门控回报上传附件被拦下的原因()
    {
        ChatTurnAttachment img = new ChatTurnAttachment("a.png", "uploads/a.png", "image/png", 12L);
        ChatTurnAttachment pdf = new ChatTurnAttachment("a.pdf", "uploads/a.pdf", "application/pdf", 100L);
        List<MediaGateReport> got = new ArrayList<>();
        runner.composeUserText("看看", List.of(img, pdf), IMAGE, got::add);

        MediaGateReport r = got.get(0);
        assertEquals(1, r.accepted(), "只有图片进得去");
        assertEquals(1, r.rejected().size());
        assertEquals("file", r.rejected().get(0).get("modality"));
        assertEquals("当前模型不支持这种输入", r.rejected().get(0).get("reason"));
    }

    @Test
    void 上传侧与工具侧的拒绝原因同源()
    {
        ChatTurnAttachment pdf = new ChatTurnAttachment("a.pdf", "uploads/a.pdf", "application/pdf", 100L);
        List<MediaGateReport> got = new ArrayList<>();
        String text = runner.composeUserText("读这个", List.of(pdf), IMAGE, got::add);

        // 同一份文件,走上传和走工具产出必须得到同一个结论,否则用户看到两种说法
        String reasonToUser = (String) got.get(0).rejected().get(0).get("reason");
        assertEquals(MediaGateReport.reason("file", IMAGE), reasonToUser);
        assertTrue(text.contains(reasonToUser), "给模型的说明必须与给前端的原因一致: " + text);
    }

    @Test
    void 纯文本附件不产生门控回报()
    {
        ChatTurnAttachment csv = new ChatTurnAttachment("d.csv", "uploads/d.csv", "text/csv", 40L);
        List<MediaGateReport> got = new ArrayList<>();
        runner.composeUserText("看看", List.of(csv), IMAGE, got::add);

        assertTrue(got.isEmpty(), "csv 本来就不走 media 通道,没有门控判定可言");
    }

    @Test
    void 无附件时不回报()
    {
        List<MediaGateReport> got = new ArrayList<>();
        runner.composeUserText("你好", List.of(), IMAGE, got::add);
        assertTrue(got.isEmpty());
    }

    @Test
    void visionOff_userImageGetsNoticeAndNoMedia()
    {
        ChatTurnAttachment img = new ChatTurnAttachment("a.png", "uploads/a.png", "image/png", 12L);
        String text = runner.composeUserText("看看这张图", List.of(img), NONE);
        assertTrue(text.contains("[系统提示]"));
        assertTrue(text.contains("1 个图片附件"));
        assertTrue(text.contains("不要猜测内容"));
        assertFalse(text.contains("已随本条消息直接进入你的上下文"));

        List<Media> medias = runner.buildMedias("sess", List.of(img), NONE);
        assertTrue(medias.isEmpty());
    }

    /** 模型不支持文档时,PDF 退回文本清单让模型自己 read —— 与改造前一致。 */
    @Test
    void pdfFallsBackToTextListWhenFileUnsupported()
    {
        ChatTurnAttachment pdf = new ChatTurnAttachment("a.pdf", "uploads/a.pdf", "application/pdf", 100L);
        String text = runner.composeUserText("读这个", List.of(pdf), IMAGE);
        assertTrue(text.contains("uploads/a.pdf"));
        assertTrue(text.contains("application/pdf"));
        // 模型收不下就得说清楚,否则它会以为自己看过这份文档
        assertTrue(text.contains("[系统提示]"));
        assertTrue(text.contains("文档"));
        assertFalse(text.contains("已随本条消息直接进入你的上下文"));
    }

    /** 支持文档的模型:PDF 走 Media 通道,正文只标注来源。 */
    @Test
    void pdfInlinedWhenFileSupported()
    {
        ChatTurnAttachment pdf = new ChatTurnAttachment("a.pdf", "uploads/a.pdf", "application/pdf", 100L);
        String text = runner.composeUserText("读这个", List.of(pdf), IMAGE_FILE);
        assertTrue(text.contains("已随本条消息直接进入你的上下文"));
        assertTrue(text.contains("文档"));
        // 只陈述「已提供」拦不住模型,实测它照样会 read 一遍已在上下文里的文件
        assertTrue(text.contains("不要再用 read 读取"), "已直达上下文的媒体必须明确禁止重复读取: " + text);
        assertFalse(text.contains("[系统提示]"));
    }

    @Test
    void visionOn_imageGetsInlineHint()
    {
        ChatTurnAttachment img = new ChatTurnAttachment("a.png", "uploads/a.png", "image/png", 12L);
        String text = runner.composeUserText("看看", List.of(img), IMAGE);
        assertTrue(text.contains("已随本条消息直接进入你的上下文"));
        assertTrue(text.contains("不要再用 read 读取"), "已直达上下文的图片必须明确禁止重复读取: " + text);
        assertFalse(text.contains("[系统提示]"));
    }

    /** 非媒体附件(csv 等)既不进 Media,也不该触发"未送达"说明。 */
    @Test
    void plainFileAttachmentStaysSilent()
    {
        ChatTurnAttachment csv = new ChatTurnAttachment("d.csv", "uploads/d.csv", "text/csv", 40L);
        String text = runner.composeUserText("看数据", List.of(csv), IMAGE_FILE);
        assertTrue(text.contains("uploads/d.csv"));
        assertFalse(text.contains("[系统提示]"));
        assertTrue(runner.buildMedias("sess", List.of(csv), IMAGE_FILE).isEmpty());
    }

    /** 视频恒不进 Media,并给出与"模型不支持"不同的措辞。 */
    @Test
    void videoNeverInlined()
    {
        ModelInputModalities all = ModelInputModalities.parse("image,file,video,audio");
        ChatTurnAttachment mp4 = new ChatTurnAttachment("v.mp4", "uploads/v.mp4", "video/mp4", 9999L);
        String text = runner.composeUserText("看视频", List.of(mp4), all);
        assertTrue(text.contains("[系统提示]"));
        assertTrue(text.contains("该类型当前无法送入模型"));
        assertTrue(runner.buildMedias("sess", List.of(mp4), all).isEmpty());
    }

    /** mime 是从前端往返回来的,乱值不能让整轮挂掉。 */
    @Test
    void malformedMimeIsTolerated()
    {
        ChatTurnAttachment bad = new ChatTurnAttachment("x", "uploads/x", "not a mime", 1L);
        String text = runner.composeUserText("看", List.of(bad), IMAGE_FILE);
        assertTrue(text.contains("uploads/x"));
        assertTrue(runner.buildMedias("sess", List.of(bad), IMAGE_FILE).isEmpty());
    }

    @Test
    void buildMedias_emptyWhenNoAttachments()
    {
        assertEquals(List.of(), runner.buildMedias("sess", null, IMAGE));
        assertEquals(List.of(), runner.buildMedias("sess", List.of(), IMAGE));
    }
}
