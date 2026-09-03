package com.ruoyi.system.service.impl;

import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.system.domain.AiModelChannel;
import com.ruoyi.system.mapper.AiModelChannelMapper;
import com.ruoyi.system.service.IAiModelChannelService;

/**
 * 模型渠道绑定 服务层实现
 * 
 * @author ruoyi
 */
@Service
public class AiModelChannelServiceImpl implements IAiModelChannelService
{
    @Autowired
    private AiModelChannelMapper aiModelChannelMapper;

    /**
     * 查询绑定信息
     * 
     * @param id 绑定ID
     * @return 绑定信息
     */
    @Override
    public AiModelChannel selectAiModelChannelById(Long id)
    {
        return aiModelChannelMapper.selectAiModelChannelById(id);
    }

    /**
     * 查询绑定列表
     * 
     * @param aiModelChannel 绑定信息
     * @return 绑定集合
     */
    @Override
    public List<AiModelChannel> selectAiModelChannelList(AiModelChannel aiModelChannel)
    {
        return aiModelChannelMapper.selectAiModelChannelList(aiModelChannel);
    }

    /**
     * 新增绑定
     * 
     * @param aiModelChannel 绑定信息
     * @return 结果
     */
    @Override
    public int insertAiModelChannel(AiModelChannel aiModelChannel)
    {
        return aiModelChannelMapper.insertAiModelChannel(aiModelChannel);
    }

    /**
     * 保存绑定:复活软删除行,或判重后插入。
     * 解决删除后重新添加撞唯一约束 uk_model_channel 的问题。
     */
    @Override
    public int saveBinding(AiModelChannel aiModelChannel, String operator)
    {
        if (aiModelChannel.getModelId() == null)
        {
            throw new ServiceException("模型不能为空");
        }
        if (aiModelChannel.getChannelId() == null)
        {
            throw new ServiceException("渠道不能为空");
        }
        AiModelChannel existing = aiModelChannelMapper.selectAiModelChannelByPair(
            aiModelChannel.getModelId(), aiModelChannel.getChannelId());
        if (existing != null)
        {
            // 同一 (model_id, channel_id) 已存在:直接覆写配置,而不是插入撞 uk_model_channel。
            // join 模型/渠道过滤后可能出现「行在但列表看不见」的绑定,覆写同样能救回来,
            // 不能在这里报「无需重复添加」把用户挡在外面。
            AiModelChannel overwrite = new AiModelChannel();
            overwrite.setId(existing.getId());
            overwrite.setModelName(aiModelChannel.getModelName());
            overwrite.setInputPrice(aiModelChannel.getInputPrice());
            overwrite.setOutputPrice(aiModelChannel.getOutputPrice());
            overwrite.setWeight(aiModelChannel.getWeight());
            overwrite.setRetryCount(aiModelChannel.getRetryCount());
            overwrite.setStatus(aiModelChannel.getStatus());
            overwrite.setRemark(aiModelChannel.getRemark());
            overwrite.setUpdateBy(operator);
            return aiModelChannelMapper.updateAiModelChannel(overwrite);
        }
        aiModelChannel.setCreateBy(operator);
        return aiModelChannelMapper.insertAiModelChannel(aiModelChannel);
    }

    /**
     * 修改绑定
     * 
     * @param aiModelChannel 绑定信息
     * @return 结果
     */
    @Override
    public int updateAiModelChannel(AiModelChannel aiModelChannel)
    {
        return aiModelChannelMapper.updateAiModelChannel(aiModelChannel);
    }

    /**
     * 删除绑定对象
     * 
     * @param id 绑定ID
     * @return 结果
     */
    @Override
    public int deleteAiModelChannelById(Long id)
    {
        return aiModelChannelMapper.deleteAiModelChannelById(id);
    }

    /**
     * 批量删除绑定信息
     * 
     * @param ids 需要删除的绑定ID
     * @return 结果
     */
    @Override
    public int deleteAiModelChannelByIds(Long[] ids)
    {
        return aiModelChannelMapper.deleteAiModelChannelByIds(ids);
    }
}
