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
import com.ruoyi.common.core.controller.BaseController;
import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.common.core.page.TableDataInfo;
import com.ruoyi.common.enums.BusinessType;
import com.ruoyi.system.domain.AiChannel;
import com.ruoyi.system.domain.vo.AiChannelVo;
import com.ruoyi.system.service.IAiChannelService;

/**
 * 上游渠道 信息操作处理
 *
 * 列表/详情返回脱敏后的 AiChannelVo(不含真实 apiKey),
 * 新增/修改时接收明文 apiKey,Service 加密入库
 *
 * @author ruoyi
 */
@RestController
@RequestMapping("/ai/channel")
public class AiChannelController extends BaseController
{
    @Autowired
    private IAiChannelService aiChannelService;

    /**
     * 查询渠道列表(脱敏)
     */
    @GetMapping("/list")
    public TableDataInfo list(AiChannel aiChannel)
    {
        startPage();
        List<AiChannelVo> list = aiChannelService.selectAiChannelListMasked(aiChannel);
        return getDataTable(list);
    }

    /**
     * 获取渠道详细信息(脱敏)
     */
    @GetMapping(value = "/{channelId}")
    public AjaxResult getInfo(@PathVariable Long channelId)
    {
        return success(aiChannelService.selectAiChannelVoById(channelId));
    }

    /**
     * 新增渠道
     */
    @PreAuthorize("@ss.hasPermi('ai:channel:add')")
    @Log(title = "上游渠道", businessType = BusinessType.INSERT)
    @PostMapping
    public AjaxResult add(@RequestBody AiChannel aiChannel)
    {
        aiChannel.setCreateBy(getUsername());
        return toAjax(aiChannelService.insertAiChannel(aiChannel));
    }

    /**
     * 修改渠道
     */
    @PreAuthorize("@ss.hasPermi('ai:channel:edit')")
    @Log(title = "上游渠道", businessType = BusinessType.UPDATE)
    @PutMapping
    public AjaxResult edit(@RequestBody AiChannel aiChannel)
    {
        aiChannel.setUpdateBy(getUsername());
        return toAjax(aiChannelService.updateAiChannel(aiChannel));
    }

    /**
     * 删除渠道
     */
    @PreAuthorize("@ss.hasPermi('ai:channel:remove')")
    @Log(title = "上游渠道", businessType = BusinessType.DELETE)
    @DeleteMapping("/{channelIds}")
    public AjaxResult remove(@PathVariable Long[] channelIds)
    {
        return toAjax(aiChannelService.deleteAiChannelByIds(channelIds));
    }

    /**
     * 手动触发健康检查
     */
    @PreAuthorize("@ss.hasPermi('ai:channel:check')")
    @Log(title = "上游渠道", businessType = BusinessType.UPDATE)
    @PostMapping("/{channelId}/check")
    public AjaxResult check(@PathVariable Long channelId)
    {
        int result = aiChannelService.checkHealth(channelId);
        if (result == 0)
        {
            return error("渠道不存在");
        }
        return AjaxResult.success(result == 1 ? "渠道正常" : "渠道异常", result);
    }

}
