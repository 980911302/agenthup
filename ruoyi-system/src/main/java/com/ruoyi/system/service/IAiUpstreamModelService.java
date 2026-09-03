package com.ruoyi.system.service;

import com.ruoyi.system.domain.AiUpstreamModel;
import java.util.List;

/** 上游模型清单服务 */
public interface IAiUpstreamModelService
{
    List<AiUpstreamModel> list(AiUpstreamModel query);
    int syncFromUpstream(Long channelId, String operator);
    int addManual(AiUpstreamModel model, String operator);
    int update(AiUpstreamModel model, String operator);
    int deleteByIds(Long[] ids);
    int deleteByChannelId(Long channelId);
}
