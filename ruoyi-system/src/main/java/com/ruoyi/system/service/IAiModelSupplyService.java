package com.ruoyi.system.service;

import java.util.List;
import com.ruoyi.system.domain.AiModelChannel;
import com.ruoyi.system.domain.vo.SupplyCandidateResult;

/**
 * 模型供应 服务层
 *
 * 入口收进「模型管理 - 供应」弹窗,不再暴露全局绑定菜单。
 *
 * @author ruoyi
 */
public interface IAiModelSupplyService
{
    /**
     * 查询某模型的供应渠道列表
     *
     * @param modelId 模型ID
     * @return 供应列表(含渠道名)
     */
    public List<AiModelChannel> listByModelId(Long modelId);

    /**
     * 添加供应时的候选渠道：排除已绑定，只保留清单里有当前 modelCode 的启用渠道。
     */
    public SupplyCandidateResult listCandidateChannels(Long modelId);

    /**
     * 为模型添加一条渠道供应
     *
     * @param binding  供应信息(modelId/channelId 必填)
     * @param operator 操作人
     * @return 影响行数
     */
    public int addSupply(AiModelChannel binding, String operator);

    /**
     * 修改供应配置(权重/重试/价格/调用标识/状态)
     *
     * @param binding  供应信息(id 必填)
     * @param operator 操作人
     * @return 影响行数
     */
    public int updateSupply(AiModelChannel binding, String operator);

    /**
     * 删除供应(软删)
     *
     * @param ids 供应ID
     * @return 影响行数
     */
    public int removeSupply(Long[] ids);
}
