package com.ruoyi.system.service;

import java.util.List;
import java.util.Map;
import com.ruoyi.system.domain.KbKnowledge;

/**
 * 知识库 服务层
 *
 * @author ruoyi
 */
public interface IKbKnowledgeService
{
    public KbKnowledge selectKbKnowledgeById(Long kbId);

    public List<KbKnowledge> selectKbKnowledgeList(KbKnowledge kbKnowledge);

    /** 工作台聚合列表 + 摘要 */
    public Map<String, Object> selectWorkbench(KbKnowledge query);

    /** 知识库详情概览（文档/使用/图谱/待办聚合） */
    public Map<String, Object> selectOverview(Long kbId);

    public int insertKbKnowledge(KbKnowledge kbKnowledge);

    public int updateKbKnowledge(KbKnowledge kbKnowledge);

    public int deleteKbKnowledgeById(Long kbId);

    public int deleteKbKnowledgeByIds(Long[] kbIds);

    /** 删除影响预览：文档数 */
    public Map<String, Object> deleteImpactPreview(Long kbId);

    /** 使用情况：成员 + 范围 */
    public Map<String, Object> selectUsage(Long kbId);

    /** 添加/更新成员角色 */
    public void upsertMember(Long kbId, Long userId, String role);

    /** 移除成员 */
    public void removeMember(Long kbId, Long userId);

    /** 转移负责人 */
    public void transferOwner(Long kbId, Long newOwnerUserId);

    /**
     * 成员候选搜索。返回 { rows, total }；Controller 组装 TableDataInfo，不二次分页。
     */
    public Map<String, Object> searchMemberCandidates(Long kbId, String keyword, int pageNum, int pageSize);
}