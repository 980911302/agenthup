-- 语音合成技能(skill_code=tts)。不自动挂到智能体,在技能管理里改正文即可。
-- 正文与 Java 侧模板保持同源,改动请同步 MediaGenSkills / TtsVoiceCatalog。
INSERT INTO ai_skill (skill_code, skill_name, category, description, prompt_template, sort, status, create_by, create_time, del_flag)
SELECT 'tts', '语音合成', '语音',
       '怎么选音色、怎么用情绪标签控制语气与节奏。调用 speak 前读。',
       '# 语音合成

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
',
       0, '0', 'admin', now(), '0'
FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM ai_skill WHERE skill_code = 'tts');
