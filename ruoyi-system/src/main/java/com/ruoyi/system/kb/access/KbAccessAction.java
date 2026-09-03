package com.ruoyi.system.kb.access;

/**
 * 知识库访问动作（KB-PV2-00）。
 */
public enum KbAccessAction
{
    /** 查看详情、列表、文档、图谱概览 */
    READ,
    /** 检索、会话选库(USE) */
    USE,
    /** 上传/重处理文档、编辑库基本信息 */
    WRITE,
    /** 重建、图谱抽取、索引回滚等管理操作 */
    MANAGE,
    /** 删除库或文档 */
    DELETE
}
