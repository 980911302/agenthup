package com.ruoyi.system.kb.eval;

import java.util.ArrayList;
import java.util.List;

/** 评测 JSON 模型（轻量 POJO，由 fastjson 反序列化）。 */
public final class EvalModels
{
    private EvalModels()
    {
    }

    public static class ExpectedIr
    {
        public String docId;
        public String fileName;
        public String sourceType;
        public int minBlocks;
        public List<String> requiredHeadings = new ArrayList<>();
        public List<String> requiredSubstrings = new ArrayList<>();
        public List<String> requiredBlockTypes = new ArrayList<>();
        public List<String> requiredSourceLabels = new ArrayList<>();
        public List<String> forbiddenSubstrings = new ArrayList<>();
        public Integer minPageNumber;
        public Integer maxPageNumber;
    }

    public static class QuerySet
    {
        public String version;
        public String description;
        public List<EvalQuery> queries = new ArrayList<>();
    }

    public static class EvalQuery
    {
        public String queryId;
        public String question;
        public String type;
        public List<RelevantAnchor> relevantAnchors = new ArrayList<>();
        public List<String> goldFacts = new ArrayList<>();
        public List<String> allowedAnswers = new ArrayList<>();
        public boolean needsGraph;
        public boolean needsGlobalCommunity;
    }

    public static class RelevantAnchor
    {
        public String docId;
        public List<String> mustContain = new ArrayList<>();
    }

    public static class IndexedChunk
    {
        public String chunkKey;
        public String docId;
        public String fileName;
        public int chunkIndex;
        public String content;
        public String headingPath;
        public String blockType;
        public String sourceLabel;
        public Integer sourcePageFrom;
        public Integer sourcePageTo;

        public IndexedChunk(String docId, String fileName, int chunkIndex, String content,
            String headingPath, String blockType, String sourceLabel,
            Integer sourcePageFrom, Integer sourcePageTo)
        {
            this.docId = docId;
            this.fileName = fileName;
            this.chunkIndex = chunkIndex;
            this.content = content == null ? "" : content;
            this.headingPath = headingPath;
            this.blockType = blockType;
            this.sourceLabel = sourceLabel;
            this.sourcePageFrom = sourcePageFrom;
            this.sourcePageTo = sourcePageTo;
            this.chunkKey = docId + "#" + chunkIndex;
        }
    }

    public static class RankedHit
    {
        public IndexedChunk chunk;
        public double score;
        public String channel;

        public RankedHit(IndexedChunk chunk, double score, String channel)
        {
            this.chunk = chunk;
            this.score = score;
            this.channel = channel;
        }
    }
}
