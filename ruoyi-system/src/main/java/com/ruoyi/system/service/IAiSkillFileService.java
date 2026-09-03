package com.ruoyi.system.service;

import java.nio.file.Path;
import java.util.List;
import org.springframework.web.multipart.MultipartFile;
import com.ruoyi.system.domain.AiSkillFile;

/**
 * 技能附件:技能目录里的参考文件(渐进披露的第三层)。
 *
 * <p>与知识库的区别:知识库是向量检索、答不准确要看相似度;技能附件是<b>精确读取</b> ——
 * 模型拿到路径用 read 打开,内容一字不差。操作规则、字段清单、模板这类东西不能靠相似度召回。
 *
 * @author ruoyi
 */
public interface IAiSkillFileService
{
    /** 某技能的附件清单(按路径排序,顺序稳定)。 */
    List<AiSkillFile> listBySkill(Long skillId);

    /**
     * 上传一个附件。同名覆盖(按 skillId + relPath 判重),元数据行复用不新增。
     *
     * @param relPath 为空时取上传文件的原始名
     * @param summary 一句话说明,会进 loadSkill 的清单给模型看;为空则只给路径
     */
    AiSkillFile upload(Long skillId, MultipartFile file, String relPath, String summary, String createBy);

    /** 逻辑删除元数据并删除落盘文件;文件删不掉只告警,不阻塞。 */
    void delete(Long fileId);

    /** 技能被删除时清理其全部附件。 */
    void deleteBySkillId(Long skillId);

    /**
     * 把技能附件拷贝进会话沙箱的 {@code .skills/{skillCode}/} 下,返回实际拷贝的文件数。
     *
     * <p><b>为什么是拷贝而不是直接读源目录</b>:{@code WorkspaceSandbox} 按 sessionId 硬隔离,
     * 越界校验是本系统的安全底线,不能为了读技能文件去松动它。拷贝一份进沙箱后,
     * 现成的 read/grep/find 全部可用,一行工具代码都不用改。
     *
     * <p>副作用是老会话拿到的是当时那份快照 —— 这正是想要的:技能事后被改被删,
     * 不该让历史会话里已经在按它办事的模型突然换一套规则。
     *
     * @param sessionRoot 会话沙箱根;为 null 时直接返回 0(无会话上下文,不拷贝)
     */
    int copyToSession(Long skillId, String skillCode, Path sessionRoot);

    /**
     * 生成给模型看的附件清单文本,追加在 {@code loadSkill} 返回值末尾。
     *
     * <p>只给路径和一句话说明,不给内容 —— 这是渐进披露的关键:附件动辄几千 token,
     * 全塞进去就退化成「把整个技能内联进上下文」,那正是 loadSkill 当初要解决的问题。
     *
     * @return 无附件时返回空串
     */
    String describeForPrompt(Long skillId, String skillCode);
}
