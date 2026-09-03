package com.ruoyi.system.domain;

import java.util.Date;

/**
 * 文档图谱抽取状态 kb_doc_graph(与 kb_document.parse_status 解耦)。
 */
public class KbDocGraph
{
    private Long docId;
    private Long kbId;
    private String graphStatus;
    private String graphStep;
    private Integer progress;
    private Integer chunkTotal;
    private Integer chunkDone;
    private Integer entityCount;
    private Integer relationCount;
    private String extractModel;
    private String errorType;
    private String errorMsg;
    private Date startedAt;
    private Date finishedAt;

    /** 当前生效的 kb_graph_run.run_id（血统 v2） */
    private Long activeRunId;

    /** 文档图代数,单调递增 */
    private Long generation;

    /** 解析/切片/抽取/prompt/model 指纹摘要 */
    private String graphVersion;

    /** 非表列:文档名 */
    private String docName;

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

    public String getGraphStatus()
    {
        return graphStatus;
    }

    public void setGraphStatus(String graphStatus)
    {
        this.graphStatus = graphStatus;
    }

    public String getGraphStep()
    {
        return graphStep;
    }

    public void setGraphStep(String graphStep)
    {
        this.graphStep = graphStep;
    }

    public Integer getProgress()
    {
        return progress;
    }

    public void setProgress(Integer progress)
    {
        this.progress = progress;
    }

    public Integer getChunkTotal()
    {
        return chunkTotal;
    }

    public void setChunkTotal(Integer chunkTotal)
    {
        this.chunkTotal = chunkTotal;
    }

    public Integer getChunkDone()
    {
        return chunkDone;
    }

    public void setChunkDone(Integer chunkDone)
    {
        this.chunkDone = chunkDone;
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

    public String getExtractModel()
    {
        return extractModel;
    }

    public void setExtractModel(String extractModel)
    {
        this.extractModel = extractModel;
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

    public Long getActiveRunId()
    {
        return activeRunId;
    }

    public void setActiveRunId(Long activeRunId)
    {
        this.activeRunId = activeRunId;
    }

    public Long getGeneration()
    {
        return generation;
    }

    public void setGeneration(Long generation)
    {
        this.generation = generation;
    }

    public String getGraphVersion()
    {
        return graphVersion;
    }

    public void setGraphVersion(String graphVersion)
    {
        this.graphVersion = graphVersion;
    }

    public String getDocName()
    {
        return docName;
    }

    public void setDocName(String docName)
    {
        this.docName = docName;
    }
}
