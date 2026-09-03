package com.ruoyi.system.service;

import java.util.List;
import com.ruoyi.system.domain.AiModelChannel;

/**
 * 模型渠道绑定 服务层
 * 
 * @author ruoyi
 */
public interface IAiModelChannelService
{
    /**
     * 查询绑定信息
     * 
     * @param id 绑定ID
     * @return 绑定信息
     */
    public AiModelChannel selectAiModelChannelById(Long id);

    /**
     * 查询绑定列表
     * 
     * @param aiModelChannel 绑定信息
     * @return 绑定集合
     */
    public List<AiModelChannel> selectAiModelChannelList(AiModelChannel aiModelChannel);

    /**
     * 新增绑定
     *
     * @param aiModelChannel 绑定信息
     * @return 结果
     */
    public int insertAiModelChannel(AiModelChannel aiModelChannel);

    /**
     * 保存绑定(新增或复活):
     * 若 (model_id, channel_id) 已有行(含软删/幽灵有效行),则复活并覆写配置;
     * 否则插入。统一处理唯一约束,避免删除后重加失败。
     *
     * @param aiModelChannel 绑定信息(modelId/channelId + 配置)
     * @param operator 操作人
     * @return 结果
     */
    public int saveBinding(AiModelChannel aiModelChannel, String operator);

    /**
     * 修改绑定
     * 
     * @param aiModelChannel 绑定信息
     * @return 结果
     */
    public int updateAiModelChannel(AiModelChannel aiModelChannel);

    /**
     * 删除绑定信息
     * 
     * @param id 绑定ID
     * @return 结果
     */
    public int deleteAiModelChannelById(Long id);

    /**
     * 批量删除绑定信息
     * 
     * @param ids 需要删除的绑定ID
     * @return 结果
     */
    public int deleteAiModelChannelByIds(Long[] ids);
}
