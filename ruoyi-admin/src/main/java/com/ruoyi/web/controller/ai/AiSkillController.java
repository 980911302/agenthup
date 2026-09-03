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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import com.ruoyi.common.annotation.Log;
import com.ruoyi.common.core.controller.BaseController;
import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.common.core.page.TableDataInfo;
import com.ruoyi.common.enums.BusinessType;
import com.ruoyi.system.domain.AiSkill;
import com.ruoyi.system.domain.vo.DesktopViews;
import com.ruoyi.system.service.IAiSkillFileService;
import com.ruoyi.system.service.IAiSkillService;

/**
 * 技能 信息操作处理
 *
 * @author ruoyi
 */
@RestController
@RequestMapping("/ai/skill")
public class AiSkillController extends BaseController
{
    @Autowired
    private IAiSkillService aiSkillService;

    @Autowired
    private IAiSkillFileService aiSkillFileService;

    /**
     * 查询技能列表
     */
    @GetMapping("/list")
    public TableDataInfo list(AiSkill aiSkill)
    {
        startPage();
        if (!isAdmin())
        {
            return getDataTable(aiSkillService.selectAvailableSkillsForUser(getUserId(), null));
        }
        List<AiSkill> list = aiSkillService.selectAiSkillList(aiSkill);
        return getDataTable(list);
    }

    /**
     * 桌面端 @ 技能选择器：只返回当前用户已经拥有的技能。
     * 公共技能属于资源目录，必须先添加到“我的”之后才可在聊天中使用。
     */
    @GetMapping("/options")
    public AjaxResult options()
    {
        return success(aiSkillService.selectAvailableSkillsForUser(getUserId(), null).stream()
                .filter(skill -> "PRIVATE".equalsIgnoreCase(skill.getVisibility()))
                .map(DesktopViews::skillOption).toList());
    }

    /**
     * 桌面端资源库：返回公共技能和当前用户自己的私有技能。
     * 与后台管理 list 分开，避免管理员登录桌面端时看到其他用户的私有技能。
     */
    @GetMapping("/desktop")
    public AjaxResult desktop()
    {
        return success(aiSkillService.selectAvailableSkillsForUser(getUserId(), null));
    }

    /**
     * 获取技能详细信息
     */
    @GetMapping(value = "/{skillId}")
    public AjaxResult getInfo(@PathVariable Long skillId)
    {
        AiSkill skill = requireReadable(skillId);
        return success(skill);
    }

    /**
     * 新增技能
     */
    @Log(title = "技能管理", businessType = BusinessType.INSERT)
    @PostMapping
    public AjaxResult add(@RequestBody AiSkill aiSkill)
    {
        normalizeOwnership(aiSkill, true);
        aiSkill.setCreateBy(getUsername());
        return toAjax(aiSkillService.insertAiSkill(aiSkill));
    }

    /**
     * 修改技能
     */
    @Log(title = "技能管理", businessType = BusinessType.UPDATE)
    @PutMapping
    public AjaxResult edit(@RequestBody AiSkill aiSkill)
    {
        requireManage(aiSkill.getSkillId());
        normalizeOwnership(aiSkill, false);
        aiSkill.setUpdateBy(getUsername());
        return toAjax(aiSkillService.updateAiSkill(aiSkill));
    }

    /**
     * 删除技能
     */
    @Log(title = "技能管理", businessType = BusinessType.DELETE)
    @DeleteMapping("/{skillIds}")
    public AjaxResult remove(@PathVariable Long[] skillIds)
    {
        if (skillIds != null)
        {
            for (Long skillId : skillIds) requireManage(skillId);
        }
        int rows = aiSkillService.deleteAiSkillByIds(skillIds);
        // 技能没了,附件元数据也一起逻辑删除:否则重建同 id 的技能会捡到上一个技能的附件
        if (skillIds != null)
        {
            for (Long skillId : skillIds) aiSkillFileService.deleteBySkillId(skillId);
        }
        return toAjax(rows);
    }

    /**
     * 技能附件清单(渐进披露第三层)。
     *
     * <p>只回元数据,不回内容:内容由模型在会话里用 read 打开 ——
     * 这正是技能附件与「把文档内联进提示词」的区别。
     */
    @GetMapping("/{skillId}/files")
    public AjaxResult listFiles(@PathVariable Long skillId)
    {
        requireReadable(skillId);
        return success(aiSkillFileService.listBySkill(skillId));
    }

    /**
     * 上传技能附件。同名覆盖。
     *
     * <p>V1 只收文本参考文件,不收脚本 —— 公共技能带可执行脚本等于让别人的代码
     * 跑在你的会话里,要支持得先有审核机制。
     *
     * @param relPath 技能目录内的相对路径;不传取上传文件原名
     * @param summary 一句话说明,会进 loadSkill 的清单给模型看
     */
    @Log(title = "技能附件", businessType = BusinessType.INSERT)
    @PostMapping("/{skillId}/files")
    public AjaxResult uploadFile(@PathVariable Long skillId,
                                 @RequestParam("file") MultipartFile file,
                                 @RequestParam(required = false) String relPath,
                                 @RequestParam(required = false) String summary)
    {
        requireManage(skillId);
        return success(aiSkillFileService.upload(skillId, file, relPath, summary, getUsername()));
    }

    @Log(title = "技能附件", businessType = BusinessType.DELETE)
    @DeleteMapping("/{skillId}/files/{fileId}")
    public AjaxResult removeFile(@PathVariable Long skillId, @PathVariable Long fileId)
    {
        requireManage(skillId);
        aiSkillFileService.delete(fileId);
        return success();
    }

    /** 技能默认归创建者私有;公共技能是显式发布的类型,只能由管理员设置。 */
    private void normalizeOwnership(AiSkill skill, boolean creating)
    {
        boolean admin = isAdmin();
        String visibility = skill.getVisibility();
        if (visibility == null || visibility.isBlank())
        {
            // 不传可见范围一律私有:桌面端资源库「我的技能」与后台新建都默认归创建者本人
            visibility = "PRIVATE";
        }
        visibility = visibility.trim().toUpperCase(java.util.Locale.ROOT);
        if (!"PUBLIC".equals(visibility) && !"PRIVATE".equals(visibility))
        {
            throw new com.ruoyi.common.exception.ServiceException("技能可见范围只能是 PUBLIC 或 PRIVATE");
        }
        if (!admin && "PUBLIC".equals(visibility))
        {
            throw new com.ruoyi.common.exception.ServiceException("只有管理员可以发布公共技能");
        }
        skill.setVisibility(visibility);
        // 私有技能必记归属用户;公共技能创建时清空归属(编辑时保留不覆盖)
        if ("PRIVATE".equals(visibility))
        {
            skill.setOwnerUserId(getUserId());
        }
        else if (creating)
        {
            skill.setOwnerUserId(null);
        }
    }

    private AiSkill requireReadable(Long skillId)
    {
        AiSkill skill = aiSkillService.selectAiSkillById(skillId);
        if (skill == null)
        {
            throw new com.ruoyi.common.exception.ServiceException("技能不存在");
        }
        if (isAdmin()) return skill;
        boolean readable = aiSkillService.selectAvailableSkillsForUser(getUserId(),
                java.util.List.of(skillId)).stream().anyMatch(item -> skillId.equals(item.getSkillId()));
        if (!readable)
        {
            throw new com.ruoyi.common.exception.ServiceException("无权访问该技能");
        }
        return skill;
    }

    private void requireManage(Long skillId)
    {
        AiSkill skill = requireReadable(skillId);
        if (isAdmin()) return;
        if (!"PRIVATE".equals(skill.getVisibility()) || !getUserId().equals(skill.getOwnerUserId()))
        {
            throw new com.ruoyi.common.exception.ServiceException("只能修改自己的私有技能");
        }
    }

    private boolean isAdmin()
    {
        return com.ruoyi.common.utils.SecurityUtils.isAdmin(getUserId());
    }
}
