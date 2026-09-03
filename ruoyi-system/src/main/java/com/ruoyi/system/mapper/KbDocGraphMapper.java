package com.ruoyi.system.mapper;

import java.util.List;
import com.ruoyi.system.domain.KbDocGraph;

/**
 * 文档图谱状态 数据层
 */
public interface KbDocGraphMapper
{
    KbDocGraph selectByDocId(Long docId);

    List<KbDocGraph> selectByKbId(Long kbId);

    int upsert(KbDocGraph row);

    int updateProgress(KbDocGraph row);

    int deleteByDocId(Long docId);

    int deleteByKbId(Long kbId);
}
