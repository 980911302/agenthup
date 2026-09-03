package com.ruoyi.system.domain;

import java.util.Date;

/**
 * 图抽取运行记录 kb_graph_run（证据血统 v2）。
 * <p>KB-GR-02 仅落库模型；生产写路径仍走旧 Entity/RELATED，见 ADR。
 */
public class KbGraphRun
{
    private Long runId;
    private Long kbId;
    private Long docId;
    private Long generation;
    private String sourceContentHash;
    private String parserVersion;
    private String chunkParamsHash;
    private String extractorVersion;
    private String promptVersion;
    private String modelCode;
    private String status;
    private String step;
    private String errorType;
    private String errorMsg;
    private Integer entityCount;
    private Integer relationCount;
    private Integer evidenceCount;
    private String extractOutcome;
    private Date startedAt;
    private Date finishedAt;
    private Date createTime;

    public Long getRunId()
    {
        return runId;
    }

    public void setRunId(Long runId)
    {
        this.runId = runId;
    }

    public Long getKbId()
    {
        return kbId;
    }

    public void setKbId(Long kbId)
    {
        this.kbId = kbId;
    }

    public Long getDocId()
    {
        return docId;
    }

    public void setDocId(Long docId)
    {
        this.docId = docId;
    }

    public Long getGeneration()
    {
        return generation;
    }

    public void setGeneration(Long generation)
    {
        this.generation = generation;
    }

    public String getSourceContentHash()
    {
        return sourceContentHash;
    }

    public void setSourceContentHash(String sourceContentHash)
    {
        this.sourceContentHash = sourceContentHash;
    }

    public String getParserVersion()
    {
        return parserVersion;
    }

    public void setParserVersion(String parserVersion)
    {
        this.parserVersion = parserVersion;
    }

    public String getChunkParamsHash()
    {
        return chunkParamsHash;
    }

    public void setChunkParamsHash(String chunkParamsHash)
    {
        this.chunkParamsHash = chunkParamsHash;
    }

    public String getExtractorVersion()
    {
        return extractorVersion;
    }

    public void setExtractorVersion(String extractorVersion)
    {
        this.extractorVersion = extractorVersion;
    }

    public String getPromptVersion()
    {
        return promptVersion;
    }

    public void setPromptVersion(String promptVersion)
    {
        this.promptVersion = promptVersion;
    }

    public String getModelCode()
    {
        return modelCode;
    }

    public void setModelCode(String modelCode)
    {
        this.modelCode = modelCode;
    }

    public String getStatus()
    {
        return status;
    }

    public void setStatus(String status)
    {
        this.status = status;
    }

    public String getStep()
    {
        return step;
    }

    public void setStep(String step)
    {
        this.step = step;
    }

    public String getErrorType()
    {
        return errorType;
    }

    public void setErrorType(String errorType)
    {
        this.errorType = errorType;
    }

    public String getErrorMsg()
    {
        return errorMsg;
    }

    public void setErrorMsg(String errorMsg)
    {
        this.errorMsg = errorMsg;
    }

    public Integer getEntityCount()
    {
        return entityCount;
    }

    public void setEntityCount(Integer entityCount)
    {
        this.entityCount = entityCount;
    }

    public Integer getRelationCount()
    {
        return relationCount;
    }

    public void setRelationCount(Integer relationCount)
    {
        this.relationCount = relationCount;
    }

    public Integer getEvidenceCount()
    {
        return evidenceCount;
    }

    public void setEvidenceCount(Integer evidenceCount)
    {
        this.evidenceCount = evidenceCount;
    }

    public String getExtractOutcome()
    {
        return extractOutcome;
    }

    public void setExtractOutcome(String extractOutcome)
    {
        this.extractOutcome = extractOutcome;
    }

    public Date getStartedAt()
    {
        return startedAt;
    }

    public void setStartedAt(Date startedAt)
    {
        this.startedAt = startedAt;
    }

    public Date getFinishedAt()
    {
        return finishedAt;
    }

    public void setFinishedAt(Date finishedAt)
    {
        this.finishedAt = finishedAt;
    }

    public Date getCreateTime()
    {
        return createTime;
    }

    public void setCreateTime(Date createTime)
    {
        this.createTime = createTime;
    }
}
