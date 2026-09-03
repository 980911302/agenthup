package com.ruoyi.adapter.ai.model;

import com.ruoyi.ai.contract.core.InvocationContext;
import com.ruoyi.ai.contract.model.ModelCapabilities;
import com.ruoyi.ai.contract.model.ModelCapability;
import com.ruoyi.ai.contract.model.ModelRequest;
import com.ruoyi.ai.contract.model.ModelRouteResolver;
import com.ruoyi.ai.contract.model.ResolvedModel;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.system.domain.AiChannel;
import com.ruoyi.system.domain.AiModel;
import com.ruoyi.system.domain.AiModelChannel;
import com.ruoyi.system.service.IAiChannelService;
import com.ruoyi.system.service.IAiModelService;
import com.ruoyi.system.service.IAiModelSupplyService;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Component;

/** 数据库模型/供应配置到稳定路由契约的适配器。 */
@Component
public class DbModelRouteResolver implements ModelRouteResolver
{
    private final IAiModelService modelService;
    private final IAiModelSupplyService supplyService;
    private final IAiChannelService channelService;

    public DbModelRouteResolver(IAiModelService modelService, IAiModelSupplyService supplyService,
                                IAiChannelService channelService)
    {
        this.modelService = modelService;
        this.supplyService = supplyService;
        this.channelService = channelService;
    }

    @Override
    public ResolvedModel resolve(ModelRequest request, InvocationContext context)
    {
        AiModel model = findModel(request.modelRef());
        if (model == null || !"0".equals(model.getStatus()))
        {
            throw new ServiceException("模型不存在或已停用: " + request.modelRef());
        }
        if (!matches(model, request.capability()))
        {
            throw new ServiceException("模型能力不匹配: " + request.capability());
        }
        AiModelChannel supply = activeSupply(model.getModelId());
        AiChannel channel = channelService.selectAiChannelById(supply.getChannelId());
        if (channel == null)
        {
            throw new ServiceException("模型渠道不存在");
        }
        ModelCapabilities capabilities = new ModelCapabilities(Set.of(request.capability()),
                request.capability() == ModelCapability.CHAT, request.capability() == ModelCapability.CHAT,
                "1".equals(model.getVisionEnabled()),
                model.getContextWindow() == null ? 0 : model.getContextWindow());
        return new ResolvedModel("channel:" + channel.getChannelId(), supply.getModelName(), capabilities);
    }

    private AiModel findModel(String ref)
    {
        try
        {
            return modelService.selectAiModelById(Long.valueOf(ref));
        }
        catch (NumberFormatException ignored)
        {
            return modelService.selectByModelCode(ref);
        }
    }

    private AiModelChannel activeSupply(Long modelId)
    {
        List<AiModelChannel> supplies = supplyService.listByModelId(modelId);
        if (supplies == null) supplies = List.of();
        return supplies.stream()
                .filter(s -> "0".equals(s.getStatus()))
                .sorted((a, b) -> Integer.compare(weight(b), weight(a)))
                .filter(s -> {
                    AiChannel channel = channelService.selectAiChannelById(s.getChannelId());
                    return channel != null && "0".equals(channel.getStatus());
                })
                .findFirst()
                .orElseThrow(() -> new ServiceException("模型暂无可用渠道"));
    }

    private static int weight(AiModelChannel supply)
    {
        return supply.getWeight() == null ? 0 : supply.getWeight();
    }

    private static boolean matches(AiModel model, ModelCapability capability)
    {
        return model.getModelType() != null
                && model.getModelType().equalsIgnoreCase(capability.name());
    }
}
