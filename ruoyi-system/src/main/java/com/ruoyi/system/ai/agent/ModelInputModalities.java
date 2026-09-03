package com.ruoyi.system.ai.agent;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import org.springframework.util.MimeType;
import com.ruoyi.system.domain.AiModel;

/**
 * 模型的输入模态集合 —— 取代原先「支持/不支持视觉」的单一布尔。
 *
 * <p><b>为什么不是一个开关</b>:输入模态之间不是包含关系,不能做成等级。实测 OpenRouter
 * 417 个模型跑出 12 种组合,反例明确:{@code openai/gpt-audio} 支持音频但不支持图片,
 * {@code openai/o3-mini} 支持文档但不支持图片,{@code moonshotai/kimi-k3} 支持视频但不
 * 支持文档。所以只能是四个互相独立的声明。
 *
 * <p><b>两层判定</b>:{@link #supports} 只回答「模型声称支持吗」,而
 * {@link #accepts} 还要再问一句「这个 MIME 我们真的送得出去吗」。两者必须分开,
 * 因为传输层的能力比模型的能力窄 —— 见下。
 *
 * <p><b>传输层约束(Spring AI 1.1.5 + OpenAI 协议)</b>:
 * {@code OpenAiChatModel.mapToMediaContent} 的分派是
 * <pre>
 *   audio/mp3        → InputAudio(MP3)
 *   audio/wav        → InputAudio(WAV)
 *   application/pdf  → InputFile(filename, base64)
 *   其它任何 MIME    → ImageUrl(data URI)      ← 兜底
 * </pre>
 * 兜底分支是问题所在:传一个 {@code video/mp4} 进去不会报错,会被当成
 * {@code image_url} 发出 {@code data:video/mp4;base64,...} —— 静默错误,比抛异常更难查。
 * 因此 {@link #TRANSPORTABLE_MIMES} 是一份白名单而非黑名单,不在其中的一律不发,
 * 哪怕模型声明支持该模态。视频就属于这一类:声明得了,发不出去。
 *
 * @author ruoyi
 */
public final class ModelInputModalities
{
    /** 图片。对应 image_url。 */
    public static final String IMAGE = "image";

    /** 文档。仅 PDF,对应 input_file。 */
    public static final String FILE = "file";

    /** 视频。当前传输层无法送达,见类注释。 */
    public static final String VIDEO = "video";

    /** 音频。仅 mp3 / wav,对应 input_audio。 */
    public static final String AUDIO = "audio";

    /** 声明层的合法取值。text 是所有模型的前提,不入库也不参与判定。 */
    private static final Set<String> KNOWN = Set.of(IMAGE, FILE, VIDEO, AUDIO);

    /**
     * 传输层真正送得出去的模态。{@link #VIDEO} 不在其中 —— Spring AI 会把它错当图片。
     * 等上游支持后把 VIDEO 加进来即可,其余代码不用动。
     */
    private static final Set<String> TRANSPORTABLE = Set.of(IMAGE, FILE, AUDIO);

    private static final ModelInputModalities NONE = new ModelInputModalities(Set.of());

    private final Set<String> declared;

    private ModelInputModalities(Set<String> declared)
    {
        this.declared = declared;
    }

    /**
     * 从模型配置解析。
     *
     * <p>拿不到模型、字段为空时返回空集 —— 与原 {@code visionEnabled()} 的降级口径一致:
     * 宁可少发一张图,也不能让整轮请求被上游 400 打回。
     */
    public static ModelInputModalities of(AiModel model)
    {
        return model == null ? NONE : parse(model.getInputModalities());
    }

    /** 解析逗号分隔的声明,未知词元忽略。 */
    public static ModelInputModalities parse(String raw)
    {
        if (raw == null || raw.isBlank())
        {
            return NONE;
        }
        Set<String> set = new LinkedHashSet<>();
        for (String token : raw.split(","))
        {
            String t = token.trim().toLowerCase(Locale.ROOT);
            if (KNOWN.contains(t))
            {
                set.add(t);
            }
        }
        return set.isEmpty() ? NONE : new ModelInputModalities(Collections.unmodifiableSet(set));
    }

    /** 模型是否声明支持该模态。注意:声明支持不等于发得出去,发送前请用 {@link #accepts}。 */
    public boolean supports(String modality)
    {
        return modality != null && declared.contains(modality);
    }

    /**
     * 这份媒体能不能真的进请求 —— 模型声明支持,**且**传输层送得出去。
     *
     * <p>两个条件缺一不可。视频恒为 false:即便模型声明支持,Spring AI 也会把它
     * 错当成 image_url 发出去。
     */
    public boolean accepts(MimeType mime)
    {
        String modality = modalityOf(mime);
        if (modality == null || !TRANSPORTABLE.contains(modality))
        {
            return false;
        }
        if (!supports(modality))
        {
            return false;
        }
        return transportable(mime);
    }

    /**
     * MIME 归属哪个模态。识别不出返回 null。
     *
     * <p>比传输层宽:{@code video/mp4} 能认出是 video,但 {@link #accepts} 仍会拒绝它。
     * 这样拒绝时才能给模型一句说得清的话(「有个视频没送达」而不是「有个东西没送达」)。
     */
    public static String modalityOf(MimeType mime)
    {
        if (mime == null)
        {
            return null;
        }
        String type = mime.getType().toLowerCase(Locale.ROOT);
        String sub = mime.getSubtype().toLowerCase(Locale.ROOT);
        if ("image".equals(type))
        {
            return IMAGE;
        }
        if ("video".equals(type))
        {
            return VIDEO;
        }
        if ("audio".equals(type))
        {
            return AUDIO;
        }
        if ("application".equals(type) && "pdf".equals(sub))
        {
            return FILE;
        }
        return null;
    }

    /**
     * 这个具体 MIME 是否落在 Spring AI 能正确序列化的白名单里。
     *
     * <p>模态对了也可能不行:模型支持 audio,但 {@code audio/ogg} 依然会掉进兜底分支
     * 被当成图片。音频只认 {@code audio/mp3} 与 {@code audio/wav} 两种字面量 ——
     * 注意 <b>不是</b> 标准的 {@code audio/mpeg},Spring AI 判的就是 {@code audio/mp3}。
     */
    public static boolean transportable(MimeType mime)
    {
        String modality = modalityOf(mime);
        if (modality == null)
        {
            return false;
        }
        String full = (mime.getType() + "/" + mime.getSubtype()).toLowerCase(Locale.ROOT);
        switch (modality)
        {
            case IMAGE:
                return true;
            case FILE:
                return "application/pdf".equals(full);
            case AUDIO:
                return "audio/mp3".equals(full) || "audio/wav".equals(full);
            default:
                return false;
        }
    }

    /** 该模态是否有可能被送出去(与具体 MIME 无关),用于给模型措辞。 */
    public static boolean transportableModality(String modality)
    {
        return modality != null && TRANSPORTABLE.contains(modality);
    }

    /** 逗号分隔的声明,用于落库与日志。空集返回空串。 */
    public String asStored()
    {
        return String.join(",", declared);
    }

    /** 兼容旧口径:是否支持图片。原 {@code visionEnabled()} 语义等价于此。 */
    public boolean imageEnabled()
    {
        return supports(IMAGE);
    }

    @Override
    public String toString()
    {
        return declared.isEmpty() ? "(none)" : asStored();
    }
}
