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
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.RestController;
import com.ruoyi.common.annotation.Log;
import com.ruoyi.common.constant.HttpStatus;
import com.ruoyi.common.core.controller.BaseController;
import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.common.core.page.TableDataInfo;
import com.ruoyi.common.enums.BusinessType;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.system.domain.AiAgent;
import com.ruoyi.system.domain.vo.DesktopViews;
import com.ruoyi.system.service.AiAgentUpdates;
import com.ruoyi.system.service.IAiAgentService;

/**
 * 智能体管理 信息操作处理
 *
 * @author ruoyi
 */
@RestController
@RequestMapping("/ai/agent")
public class AiAgentController extends BaseController
{
    @Autowired
    private IAiAgentService aiAgentService;

    /**
     * 查询智能体列表(分页)
     */
    @GetMapping("/list")
    @PreAuthorize("@ss.hasPermi('ai:agent:list')")
    public TableDataInfo list(AiAgent aiAgent)
    {
        startPage();
        List<AiAgent> list = aiAgentService.selectAiAgentListForManagement(aiAgent);
        return getDataTable(list);
    }

    /**
     * 后台管理端查询全部正常状态的智能体(下拉选择用,不分页)。
     */
    @GetMapping("/listAll")
    @PreAuthorize("@ss.hasPermi('ai:agent:list')")
    public AjaxResult listAll()
    {
        AiAgent query = new AiAgent();
        query.setStatus("0");
        return success(aiAgentService.selectAiAgentListForManagement(query));
    }

    /** desktop 使用端智能体选择器：仅返回本人创建或公共的启用智能体。 */
    @GetMapping("/options")
    public AjaxResult options()
    {
        AiAgent query = new AiAgent();
        query.setStatus("0");
        List<AiAgent> list = aiAgentService.selectAiAgentList(query);
        return success(list.stream().map(DesktopViews::agentOption).toList());
    }

    /**
     * 获取智能体详细信息(含技能/工具/子智能体配置)
     */
    @GetMapping(value = "/{agentId}")
    @PreAuthorize("@ss.hasPermi('ai:agent:query')")
    public AjaxResult getInfo(@PathVariable Long agentId)
    {
        AiAgent agent = aiAgentService.selectAccessibleAiAgentById(agentId);
        if (agent == null)
        {
            throw new ServiceException("智能体不存在或无权访问", HttpStatus.FORBIDDEN);
        }
        return success(agent);
    }

    /**
     * 新增智能体
     */
    @PreAuthorize("@ss.hasPermi('ai:agent:add')")
    @Log(title = "智能体管理", businessType = BusinessType.INSERT)
    @PostMapping
    public AjaxResult add(@RequestBody AiAgent aiAgent)
    {
        aiAgent.setCreateBy(getUsername());
        return toAjax(aiAgentService.insertAiAgent(aiAgent));
    }

    /**
     * 修改智能体
     */
    @PreAuthorize("@ss.hasPermi('ai:agent:edit')")
    @Log(title = "智能体管理", businessType = BusinessType.UPDATE)
    @PutMapping
    public AjaxResult edit(@RequestBody AiAgent aiAgent)
    {
        // PUT 整单保存:清空的模型是 null,必须写成空串,否则 mapper 跳过 SET
        AiAgentUpdates.applyRestPutNulls(aiAgent);
        aiAgent.setUpdateBy(getUsername());
        return toAjax(aiAgentService.updateAiAgent(aiAgent));
    }

    /**
     * 删除智能体
     */
    @PreAuthorize("@ss.hasPermi('ai:agent:remove')")
    @Log(title = "智能体管理", businessType = BusinessType.DELETE)
    @DeleteMapping("/{agentIds}")
    public AjaxResult remove(@PathVariable Long[] agentIds)
    {
        return toAjax(aiAgentService.deleteAiAgentByIds(agentIds));
    }
}
