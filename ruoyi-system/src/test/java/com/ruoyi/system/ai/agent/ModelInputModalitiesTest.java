package com.ruoyi.system.ai.agent;

import org.junit.jupiter.api.Test;
import org.springframework.util.MimeType;
import org.springframework.util.MimeTypeUtils;
import com.ruoyi.system.domain.AiModel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ModelInputModalitiesTest
{
    @Test
    void parsesCommaSeparated()
    {
        ModelInputModalities m = ModelInputModalities.parse("image,file");
        assertTrue(m.supports(ModelInputModalities.IMAGE));
        assertTrue(m.supports(ModelInputModalities.FILE));
        assertFalse(m.supports(ModelInputModalities.AUDIO));
    }

    @Test
    void toleratesWhitespaceCaseAndUnknownTokens()
    {
        ModelInputModalities m = ModelInputModalities.parse(" IMAGE , bogus ,file ");
        assertTrue(m.supports(ModelInputModalities.IMAGE));
        assertTrue(m.supports(ModelInputModalities.FILE));
        assertEquals("image,file", m.asStored());
    }

    @Test
    void nullAndBlankMeanTextOnly()
    {
        assertFalse(ModelInputModalities.parse(null).supports(ModelInputModalities.IMAGE));
        assertFalse(ModelInputModalities.parse("").supports(ModelInputModalities.IMAGE));
        assertFalse(ModelInputModalities.parse("   ").supports(ModelInputModalities.IMAGE));
        assertEquals("", ModelInputModalities.parse(null).asStored());
    }

    /** 拿不到模型配置时按纯文本处理:宁可少发,也不能整轮 400。 */
    @Test
    void nullModelDegradesToTextOnly()
    {
        assertFalse(ModelInputModalities.of(null).supports(ModelInputModalities.IMAGE));
    }

    @Test
    void readsFromModel()
    {
        AiModel model = new AiModel();
        model.setInputModalities("image,audio");
        ModelInputModalities m = ModelInputModalities.of(model);
        assertTrue(m.imageEnabled());
        assertTrue(m.supports(ModelInputModalities.AUDIO));
    }

    @Test
    void modalityOfRecognisesTypes()
    {
        assertEquals(ModelInputModalities.IMAGE, ModelInputModalities.modalityOf(mime("image/png")));
        assertEquals(ModelInputModalities.FILE, ModelInputModalities.modalityOf(mime("application/pdf")));
        assertEquals(ModelInputModalities.VIDEO, ModelInputModalities.modalityOf(mime("video/mp4")));
        assertEquals(ModelInputModalities.AUDIO, ModelInputModalities.modalityOf(mime("audio/wav")));
        assertEquals(null, ModelInputModalities.modalityOf(mime("text/csv")));
        assertEquals(null, ModelInputModalities.modalityOf(null));
    }

    @Test
    void acceptsRequiresBothDeclarationAndTransport()
    {
        ModelInputModalities imageOnly = ModelInputModalities.parse("image");
        assertTrue(imageOnly.accepts(mime("image/png")));
        assertFalse(imageOnly.accepts(mime("application/pdf")), "未声明支持文档");

        ModelInputModalities withFile = ModelInputModalities.parse("image,file");
        assertTrue(withFile.accepts(mime("application/pdf")));
    }

    /**
     * 视频是硬约束:Spring AI 1.1.5 的 mapToMediaContent 没有视频分支,会把它兜底成
     * image_url 发出去。声明支持也必须拒,否则是个静默错误。
     */
    @Test
    void videoNeverAcceptedEvenWhenDeclared()
    {
        ModelInputModalities all = ModelInputModalities.parse("image,file,video,audio");
        assertTrue(all.supports(ModelInputModalities.VIDEO), "声明层要如实记录");
        assertFalse(all.accepts(mime("video/mp4")), "传输层必须拒绝");
        assertFalse(all.accepts(mime("video/webm")));
        assertFalse(ModelInputModalities.transportableModality(ModelInputModalities.VIDEO));
    }

    /**
     * 音频只认字面 audio/mp3 与 audio/wav —— Spring AI 判的就是这两个字符串。
     * 标准的 audio/mpeg 会掉进兜底分支被当成图片,所以必须拒。
     */
    @Test
    void audioAcceptsOnlyMp3AndWav()
    {
        ModelInputModalities audio = ModelInputModalities.parse("audio");
        assertTrue(audio.accepts(mime("audio/mp3")));
        assertTrue(audio.accepts(mime("audio/wav")));
        assertFalse(audio.accepts(mime("audio/mpeg")), "audio/mpeg 不是 Spring AI 判定的字面量");
        assertFalse(audio.accepts(mime("audio/ogg")));
    }

    /** 文档只认 PDF,其它 application/* 一律不发。 */
    @Test
    void fileAcceptsOnlyPdf()
    {
        ModelInputModalities file = ModelInputModalities.parse("file");
        assertTrue(file.accepts(mime("application/pdf")));
        assertFalse(file.accepts(mime("application/msword")));
    }

    @Test
    void acceptsHandlesNullMime()
    {
        assertFalse(ModelInputModalities.parse("image").accepts(null));
    }

    private static MimeType mime(String s)
    {
        return s == null ? null : MimeTypeUtils.parseMimeType(s);
    }
}
