package com.ruoyi.system.ai.agent;

/**
 * 生图 / 视频技能的编码与默认正文。
 * 详细规则落 {@code ai_skill},不写进工具定义;换模型时在技能管理里改。
 */
public final class MediaGenSkills
{
    public static final String IMAGE_CODE = "image";
    public static final String IMAGE_NAME = "图片生成";
    public static final String IMAGE_DESCRIPTION =
            "怎么写生图提示词、怎么用参考图保持主体一致或换背景换风格。调用 drawImage 前读。";

    public static final String VIDEO_CODE = "video";
    public static final String VIDEO_NAME = "视频生成";
    public static final String VIDEO_DESCRIPTION =
            "怎么写视频提示词与运镜、怎么用起始帧让画面动起来或接上一段。没有尾帧。调用 drawVideo 前读。";

    private MediaGenSkills()
    {
    }

    /**
     * 工具描述只回答模型关心的三件事:做什么、何时调、用法去哪查。
     *
     * <p>不要写「换模型时改哪条技能」这类维护说明 —— 那是给人看的,模型读了没用,
     * 却要占掉每一轮请求的前缀 token。维护约定写在本类的注释里。
     */
    public static String imageToolDescription()
    {
        return "生成或修改图片。用户要画图、出图、改图、换背景、做视觉素材,"
                + "或需要多张图保持同一主体时调用。参数与参考图写法见技能「" + IMAGE_NAME
                + "」,先 loadSkill 读它再调用。";
    }

    public static String videoToolDescription()
    {
        return "生成短视频。用户要做视频、生成动画、让画面动起来、让某张图动起来时调用。"
                + "参数与起始帧的写法见技能「" + VIDEO_NAME + "」,先 loadSkill 读它再调用。";
    }

    public static String imageSkillTemplate()
    {
        return """
                # 图片生成

                绑了生图模型后用 `drawImage`。先读完本技能再调用。换模型时只改本技能。

                ## 参数

                - `prompt`：画面描述。必填。
                - `imageUrls`：可选，参考图，最多 3 张。传会话工作区相对路径，如 `outputs/img-xxx.png`（上次 drawImage 的返回路径）。不要自己转成 URL。
                - `size`：可选，如 `1024x1024`（方）/ `1024x1792`（竖）/ `1792x1024`（横）。

                ## 怎么写 prompt

                按「主体 → 动作/状态 → 环境 → 构图景别 → 光线 → 风格」的顺序展开，逗号分隔短语。写具体的名词和形容词，避免「好看」「高级感」这类模型无法执行的词。

                ```
                一只橘猫蹲在窗台上，回头看向镜头，背景是雨后的城市街道，中景，侧逆光，浅景深，写实摄影风格
                ```

                要排除什么就直说（「不要文字水印」「不要多余的手指」）。风格词固定下来复用，同一批图才不会各画各的。

                ## 参考图能做什么

                纯文生图每次结果都不一样。**只要需要「和刚才那张保持某种关系」，就得传参考图**——靠文字描述复现是做不到的。

                常见用法：

                - **保持同一主体**：传它的既有图，prompt 写明保持什么不变、改什么。人物、产品、建筑、吉祥物、UI 元素都适用。
                - **换一个角度或状态**：基于同一张图改，明确「只改视角/姿态，其余特征不变」。
                - **换背景或场景**：传主体图，prompt 描述新环境与匹配的光线。
                - **风格迁移**：传风格参考图，prompt 写「沿用 @image1 的画风与配色」。
                - **多图合成**：最多 3 张，prompt 里说清从每张各取什么（哪张出主体、哪张出背景、哪张定风格）。

                用法：

                1. 先拿到参考图的工作区路径。
                2. 放进 `imageUrls`，顺序就是 `@image1`、`@image2`、`@image3`。
                3. **prompt 里必须写明每张图是什么、起什么作用**——不写，模型不知道哪张是主体哪张是背景。

                ## 一致性要点

                - 需要反复出现的主体，先定一张**基准图**并固定下来，之后所有变体都基于它改，不要重新文生图。
                - 「保持不变」的项要**逐条列出来**，泛泛说「保持一致」效果差。列什么取决于主体是什么：人物列五官、发型、体型、服装；产品列外形、材质、配色、标识位置；场景列布局、色调、光线方向。
                - 一致性描述放在 prompt 中段，不要压在最后一句。

                ## 调用

                ```
                drawImage({ "prompt": "雨后的城市街道，黄昏，广角，写实摄影", "size": "1792x1024" })

                drawImage({
                  "prompt": "@image1 是这个主体，保持其外形、材质与配色完全不变，改为放在木质桌面上，侧上方柔光",
                  "imageUrls": ["outputs/img-xxx.png"]
                })

                drawImage({
                  "prompt": "把 @image1 的产品放到 @image2 的场景里，保持产品外观与配色不变，光线匹配场景",
                  "imageUrls": ["outputs/img-product.png", "outputs/img-scene.png"]
                })
                ```

                ## 产出

                返回工作区相对路径（`outputs/img-xxx.png`），并作为附件展示给用户。后续要复用这张图就传这个路径。
                """;
    }

    public static String videoSkillTemplate()
    {
        return """
                # 视频生成

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
                """;
    }
}
