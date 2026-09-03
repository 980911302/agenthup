package com.ruoyi.system.domain;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.apache.commons.lang3.builder.ToStringBuilder;
import org.apache.commons.lang3.builder.ToStringStyle;
import com.ruoyi.common.core.domain.BaseEntity;

/**
 * 知识库文档表 kb_document
 *
 * @author ruoyi
 */
public class KbDocument extends BaseEntity
{
    private static final long serialVersionUID = 1L;

    /** 文档ID */
    private Long docId;

    /** 所属知识库ID */
    private Long kbId;

    /** 文档名称(原始文件名) */
    private String docName;

    /** 文件存储路径(/profile/upload/kb/...) */
    private String filePath;

    /** 文件大小(字节) */
    private Long fileSize;

    /** 文件类型(扩展名,小写) */
    private String fileType;

    /** 文件内容 hash(kb 内判重) */
    private String contentHash;

    /** 解析产物(IR)文件路径 */
    private String irPath;

    /** 处理状态,见 KbDocStatus */
    private String parseStatus;

    /** 当前步骤(细粒度) */
    private String parseStep;

    /** 整体进度百分比 */
    private Integer progress;

    /** 分块数 */
    private Integer chunkCount;

    /** 失败分类 */
    private String errorType;

    /** 失败阶段(parse/chunk/embed) */
    private String errorStage;

    /** 失败全文,不截断 */
    private String errorMsg;

    /** 解析器版本 */
    private String parserVersion;

    /** 文档状态(0正常 1停用) */
    private String status;

    /** 删除标志(0存在 2删除) */
    private String delFlag;

    /**
     * 产品状态（非表字段）：READY / PROCESSING / FAILED / QUEUED
     * @see com.ruoyi.system.kb.KbDocProductStatus
     */
    private String productStatus;

    /**
     * 是否可安全下载（非表字段）：路径规范化后位于当前库目录且文件存在可读时为 true。
     * 前端只按该字段显示下载入口。
     */
    private Boolean downloadable;

    public Long getDocId()
    {
        return docId;
    }

    public void setDocId(Long docId)
    {
        this.docId = docId;
    }

    public Long getKbId()
    {
        return kbId;
    }

    public void setKbId(Long kbId)
    {
        this.kbId = kbId;
    }

    @NotBlank(message = "文档名称不能为空")
    @Size(min = 0, max = 255, message = "文档名称不能超过255个字符")
    public String getDocName()
    {
        return docName;
    }

    public void setDocName(String docName)
    {
        this.docName = docName;
    }

    @NotBlank(message = "文件路径不能为空")
    @Size(min = 0, max = 500, message = "文件路径不能超过500个字符")
    public String getFilePath()
    {
        return filePath;
    }

    public void setFilePath(String filePath)
    {
        this.filePath = filePath;
    }

    public Long getFileSize()
    {
        return fileSize;
    }

    public void setFileSize(Long fileSize)
    {
        this.fileSize = fileSize;
    }

    public String getFileType()
    {
        return fileType;
    }

    public void setFileType(String fileType)
    {
        this.fileType = fileType;
    }

    public String getContentHash()
    {
        return contentHash;
    }

    public void setContentHash(String contentHash)
    {
        this.contentHash = contentHash;
    }

    public String getIrPath()
    {
        return irPath;
    }

    public void setIrPath(String irPath)
    {
        this.irPath = irPath;
    }

    public String getParseStatus()
    {
        return parseStatus;
    }

    public void setParseStatus(String parseStatus)
    {
        this.parseStatus = parseStatus;
    }

    public String getParseStep()
    {
        return parseStep;
    }

    public void setParseStep(String parseStep)
    {
        this.parseStep = parseStep;
    }

    public Integer getProgress()
    {
        return progress;
    }

    public void setProgress(Integer progress)
    {
        this.progress = progress;
    }

    public Integer getChunkCount()
    {
        return chunkCount;
    }

    public void setChunkCount(Integer chunkCount)
    {
        this.chunkCount = chunkCount;
    }

    public String getErrorType()
    {
        return errorType;
    }

    public void setErrorType(String errorType)
    {
        this.errorType = errorType;
    }

    public String getErrorStage()
    {
        return errorStage;
    }

    public void setErrorStage(String errorStage)
    {
        this.errorStage = errorStage;
    }

    public String getErrorMsg()
    {
        return errorMsg;
    }

    public void setErrorMsg(String errorMsg)
    {
        this.errorMsg = errorMsg;
    }

    public String getParserVersion()
    {
        return parserVersion;
    }

    public void setParserVersion(String parserVersion)
    {
        this.parserVersion = parserVersion;
    }

    public String getStatus()
    {
        return status;
    }

    public void setStatus(String status)
    {
        this.status = status;
    }

    public String getDelFlag()
    {
        return delFlag;
    }

    public void setDelFlag(String delFlag)
    {
        this.delFlag = delFlag;
    }

    public String getProductStatus()
    {
        return productStatus;
    }

    public void setProductStatus(String productStatus)
    {
        this.productStatus = productStatus;
    }

    public Boolean getDownloadable()
    {
        return downloadable;
    }

    public void setDownloadable(Boolean downloadable)
    {
        this.downloadable = downloadable;
    }

    @Override
    public String toString() {
        return new ToStringBuilder(this,ToStringStyle.MULTI_LINE_STYLE)
            .append("docId", getDocId())
            .append("kbId", getKbId())
            .append("docName", getDocName())
            .append("filePath", getFilePath())
            .append("fileSize", getFileSize())
            .append("fileType", getFileType())
            .append("contentHash", getContentHash())
            .append("parseStatus", getParseStatus())
            .append("parseStep", getParseStep())
            .append("progress", getProgress())
            .append("chunkCount", getChunkCount())
            .append("status", getStatus())
            .append("createBy", getCreateBy())
            .append("createTime", getCreateTime())
            .append("updateBy", getUpdateBy())
            .append("updateTime", getUpdateTime())
            .append("remark", getRemark())
            .toString();
    }
}