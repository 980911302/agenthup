package com.ruoyi.system.mapper;

import java.util.List;
import com.ruoyi.system.domain.KbKnowledge;
import com.ruoyi.system.domain.vo.KbWorkbenchItem;

/**
 * 知识库表 数据层
 *
 * @author ruoyi
 */
public interface KbKnowledgeMapper
{
    public KbKnowledge selectKbKnowledgeById(Long kbId);

    /** 负责人转移等写路径：锁定当前库行（PostgreSQL FOR UPDATE） */
    public KbKnowledge selectKbKnowledgeByIdForUpdate(Long kbId);

    public List<KbKnowledge> selectKbKnowledgeList(KbKnowledge kbKnowledge);

    /** 工作台聚合列表（含文档计数，一次 SQL） */
    public List<KbWorkbenchItem> selectWorkbenchList(KbKnowledge query);

    public int insertKbKnowledge(KbKnowledge kbKnowledge);

    public int updateKbKnowledge(KbKnowledge kbKnowledge);

    public int deleteKbKnowledgeById(Long kbId);

    public int deleteKbKnowledgeByIds(Long[] kbIds);
}