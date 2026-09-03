package com.ruoyi.system.service;

import java.util.List;
import com.ruoyi.system.domain.AiChannel;
import com.ruoyi.system.domain.vo.AiChannelVo;

/**
 * 上游渠道 服务层
 * 
 * @author ruoyi
 */
public interface IAiChannelService
{
    /**
     * 查询渠道信息(密文)
     */
    public AiChannel selectAiChannelById(Long channelId);

    /**
     * 查询渠道列表(密文,内部使用)
     */
    public List<AiChannel> selectAiChannelList(AiChannel aiChannel);

    /**
     * 查询渠道列表(脱敏,前端展示)
     */
    public List<AiChannelVo> selectAiChannelListMasked(AiChannel aiChannel);

    /**
     * 查询渠道详情(脱敏)
     */
    public AiChannelVo selectAiChannelVoById(Long channelId);

    /**
     * 新增渠道(apiKey 入库前加密)
     */
    public int insertAiChannel(AiChannel aiChannel);

    /**
     * 修改渠道(apiKey 入库前加密)
     */
    public int updateAiChannel(AiChannel aiChannel);

    /**
     * 删除渠道信息
     */
    public int deleteAiChannelById(Long channelId);

    /**
     * 批量删除渠道信息
     */
    public int deleteAiChannelByIds(Long[] channelIds);

    /**
     * 手动触发健康检查
     */
    public int checkHealth(Long channelId);

}
