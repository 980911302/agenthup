package com.ruoyi.system.domain;

import org.apache.commons.lang3.builder.ToStringBuilder;
import org.apache.commons.lang3.builder.ToStringStyle;
import com.ruoyi.common.core.domain.BaseEntity;

/**
 * 技能附件表 ai_skill_file。
 *
 * <p>技能的第三层(渐进披露):{@code ai_skill.prompt_template} 是 SKILL.md 正文,
 * 本表是它引用的参考文件。文件内容不进上下文,{@code loadSkill} 只把路径和
 * {@code summary} 告诉模型,要看再用 read 打开。
 *
 * @author ruoyi
 */
public class AiSkillFile extends BaseEntity
{
    private static final long serialVersionUID = 1L;

    /** 附件ID */
    private Long fileId;

    /** 所属技能ID */
    private Long skillId;

    /** 技能目录内相对路径(如 REFERENCE.md) */
    private String relPath;

    /** 字节数 */
    private Long fileSize;

    /** MIME 类型 */
    private String contentType;

    /** 一句话说明,进 loadSkill 的文件清单给模型看 */
    private String summary;

    /** 删除标志(0存在 2删除) */
    private String delFlag;

    public Long getFileId() { return fileId; }
    public void setFileId(Long fileId) { this.fileId = fileId; }
    public Long getSkillId() { return skillId; }
    public void setSkillId(Long skillId) { this.skillId = skillId; }
    public String getRelPath() { return relPath; }
    public void setRelPath(String relPath) { this.relPath = relPath; }
    public Long getFileSize() { return fileSize; }
    public void setFileSize(Long fileSize) { this.fileSize = fileSize; }
    public String getContentType() { return contentType; }
    public void setContentType(String contentType) { this.contentType = contentType; }
    public String getSummary() { return summary; }
    public void setSummary(String summary) { this.summary = summary; }
    public String getDelFlag() { return delFlag; }
    public void setDelFlag(String delFlag) { this.delFlag = delFlag; }

    @Override
    public String toString()
    {
        return new ToStringBuilder(this, ToStringStyle.MULTI_LINE_STYLE)
                .append("fileId", fileId)
                .append("skillId", skillId)
                .append("relPath", relPath)
                .append("fileSize", fileSize)
                .append("contentType", contentType)
                .append("summary", summary)
                .append("createBy", getCreateBy())
                .append("createTime", getCreateTime())
                .toString();
    }
}
