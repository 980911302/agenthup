package com.ruoyi.system.mapper;

import java.util.List;
import java.util.Map;
import com.ruoyi.system.domain.AiChannel;

/**
 * 上游渠道表 数据层
 * 
 * @author ruoyi
 */
public interface AiChannelMapper
{
    /**
     * 查询渠道信息
     * 
     * @param channelId 渠道ID
     * @return 渠道信息
     */
    public AiChannel selectAiChannelById(Long channelId);

    /**
     * 查询渠道列表
     * 
     * @param aiChannel 渠道信息
     * @return 渠道集合
     */
    public List<AiChannel> selectAiChannelList(AiChannel aiChannel);

    /**
     * 新增渠道
     * 
     * @param aiChannel 渠道信息
     * @return 结果
     */
    public int insertAiChannel(AiChannel aiChannel);

    /**
     * 按 channel_code 查询(含软删行)。删除后重加时发现并复活,避免撞 uk_channel_code。
     */
    public AiChannel selectAiChannelByCodeIncludeDeleted(String channelCode);

    /** 复活软删渠道行 */
    public int reactivateAiChannel(AiChannel aiChannel);

    /**
     * 修改渠道
     * 
     * @param aiChannel 渠道信息
     * @return 结果
     */
    public int updateAiChannel(AiChannel aiChannel);

    /**
     * 删除渠道
     * 
     * @param channelId 渠道ID
     * @return 结果
     */
    public int deleteAiChannelById(Long channelId);

    /**
     * 批量删除渠道
     * 
     * @param channelIds 需要删除的渠道ID
     * @return 结果
     */
    public int deleteAiChannelByIds(Long[] channelIds);

    /**
     * 渠道健康聚合(首页)。
     * @return map keys: total / enabled / healthy / unhealthy / unknown
     */
    Map<String, Object> selectChannelHealth();
}
