package com.ruoyi.system.kb.graph.provenance;

/**
 * Neo4j 证据图标签与关系类型（KB-GR-03 起双写；本类仅锁定合约）。
 */
public final class GraphProvenanceModel
{
    private GraphProvenanceModel()
    {
    }

    public static final String LABEL_TEXT_UNIT = "TextUnit";
    public static final String LABEL_ENTITY = "Entity";
    public static final String LABEL_RELATION_EVIDENCE = "RelationEvidence";

    public static final String REL_MENTIONS = "MENTIONS";
    public static final String REL_SUPPORTS = "SUPPORTS";
    public static final String REL_FROM = "FROM";
    public static final String REL_TO = "TO";
    /** 聚合查询加速边，可从证据重算 */
    public static final String REL_RELATED = "RELATED";

    /** 证据模型版本，写入图属性 extractorVersion 时用 */
    public static final String PROVENANCE_VERSION = "v2";
}
