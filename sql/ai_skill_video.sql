-- 视频生成技能(skill_code=video)。不自动挂到智能体,在技能管理里改正文即可。
-- 正文与 Java 侧模板保持同源,改动请同步 MediaGenSkills / TtsVoiceCatalog。
INSERT INTO ai_skill (skill_code, skill_name, category, description, prompt_template, sort, status, create_by, create_time, del_flag)
SELECT 'video', '视频生成', '视频',
       '怎么写视频提示词与运镜、怎么用起始帧让画面动起来或接上一段。没有尾帧。调用 drawVideo 前读。',
       '# 视频生成

绑了视频模型后用 `drawVideo`。先读完本技能再调用。换模型时只改本技能。

当前上游只认**一张起始图** `firstFrameUrl`。没有尾帧。不要传 `lastFrameUrl` / `last_frame` / 结束画面。多张身份参考图也发不出去。

## 参数

- `prompt`：视频内容。文生视频必填；纯图生视频可省略。
- `duration`：可选，秒，通常 1–15。单次生成有时长上限，更长的内容要拆成多段。
- `aspectRatio`：可选，如 `16:9` / `9:16` / `1:1`。
- `resolution`：可选，如 `720p` / `480p`。
- `firstFrameUrl`：可选，起始帧。传会话工作区相对路径，如 `outputs/img-xxx.png`。不要自己转成 URL。

兼容：若只传了 `referenceImageUrls` / `imageUrls`，会取第一张当作起始帧。不要同时塞多张指望锁身份。

## 怎么写 prompt

按「主体 → 动作 → 环境 → 镜头运动 → 风格」分层，用句号断开，帮模型分清层次。

动作要**具体且单一**，写模型能执行的物理动作。「表现出焦虑」不可拍，「反复看表、手指敲桌面」可拍。一条 prompt 只描述一个连续动作，多个动作应拆成多次生成。

镜头运动用明确的词：固定、缓推、拉远、左摇、跟随、环绕、俯拍、仰拍。不写就由模型自由发挥，画面容易乱动。

```
一位穿深色外套的人站在雨中的路口，低头看手机后抬头张望。背景是模糊的车流与霓虹。镜头缓慢推近。写实电影感，浅景深。
```

## 图生视频

想让某张图动起来，把它放进 `firstFrameUrl`。prompt 里写从这张画面开始发生什么，并逐条写清要保持的特征（五官、发型、服装或外形、材质、配色）。不要只说「保持一致」。一致性描述放 prompt 中段。

人物开口说话的画面口型容易对不上，能用旁白或不露正脸的镜头替代就替代。

## 没有尾帧，怎么接上一段

这里没有可灵那种「首帧 + 尾帧插值」。传一张结束图不会让画面过渡到那张图。

要让下一段接着上一段：抽出上一段**最后一帧**，再当 `firstFrameUrl`。不要发明 `lastFrameUrl`。

```bash
ffmpeg -sseof -0.1 -i outputs/vid-xxx.mp4 -frames:v 1 outputs/last.png
```

只有当两段是**同一个连续动作**被时长上限拆开时才这么接。换机位、换场景不要传起始帧，否则会被强行连成一镜。

## 调用

```
drawVideo({ "prompt": "海浪拍打礁石，白色浪花溅起。镜头固定。慢动作。", "duration": 6, "aspectRatio": "16:9" })

drawVideo({
  "prompt": "这个人走到桌前坐下，翻开笔记本。镜头缓慢跟随。保持图中人物的五官、发型与深色外套不变。",
  "firstFrameUrl": "outputs/img-person.png",
  "duration": 8
})

drawVideo({
  "prompt": "她继续低头书写，窗外光线渐暗。镜头固定。",
  "firstFrameUrl": "outputs/last.png"
})
```

## 产出

返回工作区相对路径（`outputs/vid-xxx.mp4`）。多段拼接、抽帧、转码走 bash 的 ffmpeg。
',
       0, '0', 'admin', now(), '0'
FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM ai_skill WHERE skill_code = 'video');

UPDATE ai_skill
SET description = '怎么写视频提示词与运镜、怎么用起始帧让画面动起来或接上一段。没有尾帧。调用 drawVideo 前读。',
    prompt_template = '# 视频生成

绑了视频模型后用 `drawVideo`。先读完本技能再调用。换模型时只改本技能。

当前上游只认**一张起始图** `firstFrameUrl`。没有尾帧。不要传 `lastFrameUrl` / `last_frame` / 结束画面。多张身份参考图也发不出去。

## 参数

- `prompt`：视频内容。文生视频必填；纯图生视频可省略。
- `duration`：可选，秒，通常 1–15。单次生成有时长上限，更长的内容要拆成多段。
- `aspectRatio`：可选，如 `16:9` / `9:16` / `1:1`。
- `resolution`：可选，如 `720p` / `480p`。
- `firstFrameUrl`：可选，起始帧。传会话工作区相对路径，如 `outputs/img-xxx.png`。不要自己转成 URL。

兼容：若只传了 `referenceImageUrls` / `imageUrls`，会取第一张当作起始帧。不要同时塞多张指望锁身份。

## 怎么写 prompt

按「主体 → 动作 → 环境 → 镜头运动 → 风格」分层，用句号断开，帮模型分清层次。

动作要**具体且单一**，写模型能执行的物理动作。「表现出焦虑」不可拍，「反复看表、手指敲桌面」可拍。一条 prompt 只描述一个连续动作，多个动作应拆成多次生成。

镜头运动用明确的词：固定、缓推、拉远、左摇、跟随、环绕、俯拍、仰拍。不写就由模型自由发挥，画面容易乱动。

```
一位穿深色外套的人站在雨中的路口，低头看手机后抬头张望。背景是模糊的车流与霓虹。镜头缓慢推近。写实电影感，浅景深。
```

## 图生视频

想让某张图动起来，把它放进 `firstFrameUrl`。prompt 里写从这张画面开始发生什么，并逐条写清要保持的特征（五官、发型、服装或外形、材质、配色）。不要只说「保持一致」。一致性描述放 prompt 中段。

人物开口说话的画面口型容易对不上，能用旁白或不露正脸的镜头替代就替代。

## 没有尾帧，怎么接上一段

这里没有可灵那种「首帧 + 尾帧插值」。传一张结束图不会让画面过渡到那张图。

要让下一段接着上一段：抽出上一段**最后一帧**，再当 `firstFrameUrl`。不要发明 `lastFrameUrl`。

```bash
ffmpeg -sseof -0.1 -i outputs/vid-xxx.mp4 -frames:v 1 outputs/last.png
```

只有当两段是**同一个连续动作**被时长上限拆开时才这么接。换机位、换场景不要传起始帧，否则会被强行连成一镜。

## 调用

```
drawVideo({ "prompt": "海浪拍打礁石，白色浪花溅起。镜头固定。慢动作。", "duration": 6, "aspectRatio": "16:9" })

drawVideo({
  "prompt": "这个人走到桌前坐下，翻开笔记本。镜头缓慢跟随。保持图中人物的五官、发型与深色外套不变。",
  "firstFrameUrl": "outputs/img-person.png",
  "duration": 8
})

drawVideo({
  "prompt": "她继续低头书写，窗外光线渐暗。镜头固定。",
  "firstFrameUrl": "outputs/last.png"
})
```

## 产出

返回工作区相对路径（`outputs/vid-xxx.mp4`）。多段拼接、抽帧、转码走 bash 的 ffmpeg。
',
    update_time = now()
WHERE skill_code = 'video' AND del_flag = '0';
