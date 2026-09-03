package com.ruoyi.system.service.impl;

import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import com.ruoyi.common.annotation.DataSource;
import com.ruoyi.common.enums.DataSourceType;
import com.ruoyi.system.domain.KbDocGraph;
import com.ruoyi.system.mapper.KbDocGraphMapper;
import com.ruoyi.system.service.IKbDocGraphService;

/**
 * 文档图谱状态服务 —— 必须走 SLAVE(PG),表不在 MySQL。
 */
@Service
@DataSource(DataSourceType.SLAVE)
public class KbDocGraphServiceImpl implements IKbDocGraphService
{
    @Autowired
    private KbDocGraphMapper kbDocGraphMapper;

    @Override
    public KbDocGraph selectByDocId(Long docId)
    {
        return kbDocGraphMapper.selectByDocId(docId);
    }

    @Override
    public List<KbDocGraph> selectByKbId(Long kbId)
    {
        return kbDocGraphMapper.selectByKbId(kbId);
    }

    @Override
    public int upsert(KbDocGraph row)
    {
        return kbDocGraphMapper.upsert(row);
    }

    @Override
    public int deleteByDocId(Long docId)
    {
        return kbDocGraphMapper.deleteByDocId(docId);
    }

    @Override
    public int deleteByKbId(Long kbId)
    {
        return kbDocGraphMapper.deleteByKbId(kbId);
    }
}
