package com.ruoyi.system.mapper;

import java.util.List;
import com.ruoyi.system.domain.AiModelChannel;

/**
 * 模型渠道绑定表 数据层
 * 
 * @author ruoyi
 */
public interface AiModelChannelMapper
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
     * 按 (model_id, channel_id) 精确查绑定,用于新增前查重 ——
     * 唯一索引 uk_model_channel 建在这两列上,重复插入会直接撞约束。
     *
     * @param modelId 模型ID
     * @param channelId 渠道ID
     * @return 绑定记录,没有则 null
     */
    public AiModelChannel selectAiModelChannelByPair(@org.apache.ibatis.annotations.Param("modelId") Long modelId,
            @org.apache.ibatis.annotations.Param("channelId") Long channelId);

    /**
     * 新增绑定
     * 
     * @param aiModelChannel 绑定信息
     * @return 结果
     */
    public int insertAiModelChannel(AiModelChannel aiModelChannel);

    /**
     * 修改绑定
     * 
     * @param aiModelChannel 绑定信息
     * @return 结果
     */
    public int updateAiModelChannel(AiModelChannel aiModelChannel);

    /**
     * 删除绑定
     * 
     * @param id 绑定ID
     * @return 结果
     */
    public int deleteAiModelChannelById(Long id);

    /**
     * 批量删除绑定
     * 
     * @param ids 需要删除的绑定ID
     * @return 结果
     */
    public int deleteAiModelChannelByIds(Long[] ids);

    /**
     * 按渠道物理删除其全部绑定(删除渠道时连带清理)。
     * @param channelIds 渠道ID集合(为空则跳过)
     * @return 受影响行数
     */
    public int deleteByChannelIds(@org.apache.ibatis.annotations.Param("channelIds") java.util.List<Long> channelIds);

    /**
     * 按模型物理删除其全部绑定(删除模型时连带清理)。
     * @param modelIds 模型ID集合(为空则跳过)
     * @return 受影响行数
     */
    public int deleteByModelIds(@org.apache.ibatis.annotations.Param("modelIds") java.util.List<Long> modelIds);

}
