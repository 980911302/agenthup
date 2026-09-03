package com.ruoyi.system.service;

import java.util.List;
import com.ruoyi.system.domain.KbDocGraph;

/**
 * 文档图谱抽取状态(数据在 PostgreSQL slave)
 */
public interface IKbDocGraphService
{
    KbDocGraph selectByDocId(Long docId);

    List<KbDocGraph> selectByKbId(Long kbId);

    int upsert(KbDocGraph row);

    int deleteByDocId(Long docId);

    int deleteByKbId(Long kbId);
}
