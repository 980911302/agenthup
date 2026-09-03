package com.ruoyi.system.ai;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * qwen-audio-3.0-tts-plus 实测可用的系统音色。
 * 详细规则放技能正文,speak 工具只留一句指引,避免常驻占 token。
 */
public final class TtsVoiceCatalog
{
    public static final String SKILL_CODE = "tts";
    public static final String SKILL_NAME = "语音合成";
    public static final String SKILL_DESCRIPTION = "换音色、加情绪标签或写风格指令前先读这个技能,再调用 speak。";

    public static final String DEFAULT_VOICE = "longanlingxin";
    public static final String DEFAULT_MALE_VOICE = "longanlufeng";

    public record Voice(String id, String name, String gender, String hint)
    {
    }

    /** 在 token-plan SpeechSynthesizer 上实测 200 的 ID。其余官方 Flash/CosyVoice ID 会 411。 */
    public static final List<Voice> COMMON = List.of(
            new Voice("longanlingxin", "龙安灵心", "女", "知心温暖,默认"),
            new Voice("longanlufeng", "龙安鲁风", "男", "明亮开朗,男声优先"),
            new Voice("longanhuan_v3.6", "龙安欢", "女", "欢脱元气")
    );

    private static final Map<String, String> BY_ID = new LinkedHashMap<>();
    private static final Map<String, String> BY_NAME = new LinkedHashMap<>();
    private static final List<Voice> BY_NAME_LENGTH;
    private static final List<Voice> BY_ID_LENGTH;

    static
    {
        for (Voice voice : COMMON)
        {
            BY_ID.put(voice.id.toLowerCase(Locale.ROOT), voice.id);
            BY_NAME.put(voice.name, voice.id);
        }
        BY_NAME_LENGTH = new ArrayList<>(COMMON);
        BY_NAME_LENGTH.sort(Comparator.comparingInt((Voice v) -> v.name.length()).reversed());
        BY_ID_LENGTH = new ArrayList<>(COMMON);
        BY_ID_LENGTH.sort(Comparator.comparingInt((Voice v) -> v.id.length()).reversed());
    }

    private TtsVoiceCatalog()
    {
    }

    public static String resolve(String requested)
    {
        if (requested == null || requested.isBlank())
        {
            return DEFAULT_VOICE;
        }
        String raw = requested.trim();
        String lower = raw.toLowerCase(Locale.ROOT);
        String compact = lower.replaceAll("[\\s_\\-]+", "");

        String byId = BY_ID.get(lower);
        if (byId != null)
        {
            return byId;
        }
        String byName = BY_NAME.get(raw);
        if (byName != null)
        {
            return byName;
        }
        for (Voice voice : BY_ID_LENGTH)
        {
            if (lower.contains(voice.id.toLowerCase(Locale.ROOT)))
            {
                return voice.id;
            }
        }
        for (Voice voice : BY_NAME_LENGTH)
        {
            if (raw.contains(voice.name))
            {
                return voice.id;
            }
        }
        if (compact.contains("元气") || compact.contains("欢"))
        {
            return "longanhuan_v3.6";
        }
        if (looksLikeOfficialFamilyId(raw) && !BY_ID.containsKey(lower))
        {
            return mentionsMale(compact) ? DEFAULT_MALE_VOICE : DEFAULT_VOICE;
        }
        if (looksLikeVoiceId(raw))
        {
            return raw;
        }
        if (mentionsMale(compact))
        {
            return DEFAULT_MALE_VOICE;
        }
        return DEFAULT_VOICE;
    }

    public static String displayName(String voiceId)
    {
        if (voiceId == null || voiceId.isBlank())
        {
            return DEFAULT_VOICE;
        }
        for (Voice voice : COMMON)
        {
            if (voice.id.equalsIgnoreCase(voiceId))
            {
                return voice.name + "（" + voice.gender + "，" + voice.hint + "）";
            }
        }
        return voiceId;
    }

    public static String toolDescription()
    {
        return "把文字合成语音。用户要配音、朗读、生成旁白或台词音频时调用。"
                + "音色名单与情绪标签写法见技能「" + SKILL_NAME + "」,先 loadSkill 读它再调用。";
    }

    public static String voiceParamDescription()
    {
        return "可选音色 ID,名单见技能「" + SKILL_NAME + "」。不传用默认女声。禁止传「男」「女」。";
    }

    public static String skillTemplate()
    {
        return """
                # 语音合成

                绑了 TTS 模型后用 `speak`。先读完本技能再调用。换模型时只改本技能即可。

                ## 参数

                - `text`：要朗读的文本。必填。情绪标签直接写在文本里，见下。
                - `voice`：可选，音色 ID，只能用下表里的三个。不传默认 `longanlingxin`。禁止传「男」「女」这类描述词。
                - `instructions`：可选，用自然语言描述声音特质（语速、音高、气质）。不能用它换性别。

                一次调用合成一段。多个角色的对白要分多次调用，每次换 `voice`。

                ## 音色（voice）

                只许用下面三个 ID（已在当前阿里百炼渠道实测可用）。其它 long* ID 会被上游拒绝。

                | ID | 名字 | 性别 | 何时用 |
                |---|---|---|---|
                | longanlingxin | 龙安灵心 | 女 | 默认、温暖、知性 |
                | longanlufeng | 龙安鲁风 | 男 | 用户要男声 |
                | longanhuan_v3.6 | 龙安欢 | 女 | 元气、活泼 |

                用户说「男声」就传 `longanlufeng`，说「女声」或不指定就 `longanlingxin`。

                ## 情绪与风格

                两种写法，可同时用。

                ### 1. 文本里嵌标签（推荐）

                把标签写进 `text`。控制类标签作用于后面整段，直到下一个控制类标签。富语言类标签只在当前位置插入拟声。

                控制类：`[sad]` 悲伤、`[amazed]` 惊叹、`[angry]` 愤怒、`[excited]` 兴奋、`[sarcastic]` 讽刺、`[curious]` 好奇、`[bored]` 无聊、`[tired]` 疲惫、`[shouting]` 大喊、`[asmr]` 耳语、`[panicked]` 恐慌、`[whispers]` 耳语、`[crying]` 哭泣、`[serious]` 严肃、`[very slowly]` 很慢、`[very fast]` 很快、`[empathetic]` 共情、`[trembling]` 颤抖、`[reluctantly]` 不情愿、`[scornful]` 轻蔑、`[mischievously]` 调皮、`[like dracula]` 阴森。

                富语言类：`[laughing]` 大笑、`[giggles]` 咯咯笑、`[sighing]` 叹息、`[gasp]` 倒吸气、`[clears throat]` 清嗓、`[cough]` 咳嗽、`[snorts]` 哼声。

                示例：
                `[excited]今天的天气真不错！[laughing]我们一起出去玩吧！`
                `[serious]请注意安全。[excited]好了，现在开始！`

                ### 2. instructions 自然语言

                `instructions` 写声音特质，例如「沉稳的中年男性，语速偏慢，音色低沉」。不要用它换男女——换性别必须改 voice。

                ## 调用

                ```
                speak({ "text": "[excited]你好，欢迎回来！", "voice": "longanlufeng" })

                speak({
                  "text": "[serious]接下来的操作请务必按顺序进行。",
                  "voice": "longanlingxin",
                  "instructions": "语速偏慢，咬字清晰，像在做安全提示"
                })
                ```

                ## 产出

                返回工作区相对路径（`outputs/aud-xxx.mp3`），并作为音频附件展示给用户。多段拼接、加背景音、转码走 bash 的 ffmpeg。
                """;
    }

    static boolean looksLikeOfficialFamilyId(String raw)
    {
        String lower = raw.trim().toLowerCase(Locale.ROOT);
        return lower.startsWith("long") || lower.startsWith("loong");
    }

    static boolean looksLikeVoiceId(String raw)
    {
        String id = raw.trim();
        if (id.length() < 4 || id.contains(" ") || id.contains("男") || id.contains("女"))
        {
            return false;
        }
        String lower = id.toLowerCase(Locale.ROOT);
        if (lower.equals("male") || lower.equals("man") || lower.equals("masculine")
                || lower.equals("female") || lower.equals("woman") || lower.equals("girl")
                || lower.equals("boy"))
        {
            return false;
        }
        return lower.contains("_v") || lower.matches("[a-z][a-z0-9_\\-]{5,}");
    }

    private static boolean mentionsMale(String compact)
    {
        if (compact.contains("female") || compact.contains("woman") || compact.contains("girl"))
        {
            return false;
        }
        return compact.contains("男") || compact.equals("male") || compact.equals("man")
                || compact.equals("boy") || compact.contains("masculine");
    }
}
