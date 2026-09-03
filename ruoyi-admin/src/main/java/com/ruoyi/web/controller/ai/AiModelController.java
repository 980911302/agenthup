package com.ruoyi.web.controller.ai;

import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import com.ruoyi.common.annotation.Log;
import com.ruoyi.common.core.controller.BaseController;
import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.common.core.page.TableDataInfo;
import com.ruoyi.common.enums.BusinessType;
import com.ruoyi.system.domain.AiModel;
import com.ruoyi.system.domain.AiModelChannel;
import com.ruoyi.system.domain.vo.DesktopViews;
import com.ruoyi.system.domain.dto.AiModelImportDto;
import com.ruoyi.system.service.IAiModelImportService;
import com.ruoyi.system.service.IAiModelService;
import com.ruoyi.system.service.IAiModelSupplyService;

/**
 * 模型 信息操作处理
 *
 * 模型只能通过「导入」创建;渠道供应收进模型管理的「供应」弹窗。
 *
 * @author ruoyi
 */
@RestController
@RequestMapping("/ai/model")
public class AiModelController extends BaseController
{
    @Autowired
    private IAiModelService aiModelService;

    @Autowired
    private IAiModelImportService aiModelImportService;

    @Autowired
    private IAiModelSupplyService aiModelSupplyService;

    /**
     * 查询模型列表
     */
    @GetMapping("/list")
    public TableDataInfo list(AiModel aiModel)
    {
        startPage();
        List<AiModel> list = aiModelService.selectAiModelList(aiModel);
        return getDataTable(list);
    }

    /** 客户端模型选择器：仅公开且启用的聊天模型，不暴露供应渠道与价格配置。 */
    @GetMapping("/options")
    public AjaxResult options()
    {
        AiModel query = new AiModel();
        query.setStatus("0");
        query.setModelType("CHAT");
        query.setVisibility("PUBLIC");
        return success(aiModelService.selectAiModelList(query).stream()
                .map(DesktopViews::modelOption).toList());
    }

    /**
     * 获取模型详细信息
     */
    @GetMapping(value = "/{modelId}")
    public AjaxResult getInfo(@PathVariable Long modelId)
    {
        return success(aiModelService.selectAiModelById(modelId));
    }

    /**
     * 修改模型
     */
    @Log(title = "模型管理", businessType = BusinessType.UPDATE)
    @PutMapping
    public AjaxResult edit(@RequestBody AiModel aiModel)
    {
        normalizeOwnership(aiModel);
        aiModel.setUpdateBy(getUsername());
        return toAjax(aiModelService.updateAiModel(aiModel));
    }

    /**
     * 删除模型
     */
    @Log(title = "模型管理", businessType = BusinessType.DELETE)
    @DeleteMapping("/{modelIds}")
    public AjaxResult remove(@PathVariable Long[] modelIds)
    {
        return toAjax(aiModelService.deleteAiModelByIds(modelIds));
    }

    /**
     * 实时查询上游渠道模型列表(导入弹窗用)
     */
    @GetMapping("/import/upstream")
    public AjaxResult upstreamModels(Long channelId)
    {
        return success(aiModelImportService.listUpstreamModels(channelId));
    }

    /**
     * 导入模型(模型不存在则创建,已存在则仅新增渠道供应)
     */
    @Log(title = "模型管理", businessType = BusinessType.IMPORT)
    @PostMapping("/import")
    public AjaxResult importModel(@RequestBody AiModelImportDto dto)
    {
        return AjaxResult.success(aiModelImportService.importModel(dto, getUsername()));
    }

    /**
     * 查询模型的供应渠道列表
     */
    @GetMapping("/{modelId}/supply")
    public AjaxResult listSupply(@PathVariable Long modelId)
    {
        return success(aiModelSupplyService.listByModelId(modelId));
    }

    /**
     * 添加供应时的候选渠道：排除已绑定，只保留清单里有当前模型的渠道。
     */
    @GetMapping("/{modelId}/supply/candidates")
    public AjaxResult listSupplyCandidates(@PathVariable Long modelId)
    {
        return success(aiModelSupplyService.listCandidateChannels(modelId));
    }

    /**
     * 为模型添加渠道供应
     */
    @Log(title = "模型供应", businessType = BusinessType.INSERT)
    @PostMapping("/{modelId}/supply")
    public AjaxResult addSupply(@PathVariable Long modelId, @RequestBody AiModelChannel binding)
    {
        binding.setModelId(modelId);
        return toAjax(aiModelSupplyService.addSupply(binding, getUsername()));
    }

    /**
     * 修改供应配置(权重/重试/价格/调用标识/状态)
     */
    @Log(title = "模型供应", businessType = BusinessType.UPDATE)
    @PutMapping("/supply")
    public AjaxResult editSupply(@RequestBody AiModelChannel binding)
    {
        return toAjax(aiModelSupplyService.updateSupply(binding, getUsername()));
    }

    /**
     * 删除供应
     */
    @Log(title = "模型供应", businessType = BusinessType.DELETE)
    @DeleteMapping("/supply/{ids}")
    public AjaxResult removeSupply(@PathVariable Long[] ids)
    {
        return toAjax(aiModelSupplyService.removeSupply(ids));
    }

    /** 模型默认公共;私有模型需记归属用户,且仅管理员可设公共。 */
    private void normalizeOwnership(AiModel aiModel)
    {
        boolean admin = isAdmin();
        String visibility = aiModel.getVisibility();
        if (visibility == null || visibility.isBlank())
        {
            visibility = "PUBLIC";
        }
        visibility = visibility.trim().toUpperCase(java.util.Locale.ROOT);
        if (!"PUBLIC".equals(visibility) && !"PRIVATE".equals(visibility))
        {
            throw new com.ruoyi.common.exception.ServiceException("模型可见范围只能是 PUBLIC 或 PRIVATE");
        }
        if (!admin && "PUBLIC".equals(visibility))
        {
            throw new com.ruoyi.common.exception.ServiceException("只有管理员可以发布公共模型");
        }
        aiModel.setVisibility(visibility);
        if ("PRIVATE".equals(visibility))
        {
            aiModel.setOwnerUserId(getUserId());
        }
        else
        {
            aiModel.setOwnerUserId(null);
        }
    }

    private boolean isAdmin()
    {
        return com.ruoyi.common.utils.SecurityUtils.isAdmin(getUserId());
    }

}
