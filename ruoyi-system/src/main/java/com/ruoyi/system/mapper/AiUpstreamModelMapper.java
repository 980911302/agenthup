package com.ruoyi.system.mapper;

import com.ruoyi.system.domain.AiUpstreamModel;
import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.Map;

/** 上游模型 Mapper */
public interface AiUpstreamModelMapper
{
    List<AiUpstreamModel> selectList(AiUpstreamModel query);
    AiUpstreamModel selectByChannelAndModelId(@Param("channelId") Long channelId,
                                              @Param("upstreamModelId") String upstreamModelId);
    List<AiUpstreamModel> selectByIds(@Param("ids") Long[] ids);
    int insertBatch(@Param("list") List<AiUpstreamModel> list);
    int insertOne(AiUpstreamModel model);
    int updateOne(AiUpstreamModel model);
    int deleteByIds(@Param("ids") Long[] ids);
    int deleteByChannelId(@Param("channelId") Long channelId);
    int deleteByChannelIds(@Param("channelIds") List<Long> channelIds);
    List<AiUpstreamModel> selectByChannelIds(@Param("channelIds") List<Long> channelIds);
    List<Map<String, Object>> countByChannelIds(@Param("channelIds") List<Long> channelIds);
    List<Long> selectChannelIdsByUpstreamModelId(@Param("upstreamModelId") String upstreamModelId);
    void createTableForTest();
    void dropTableForTest();
}
