package com.ruoyi.web.controller.ai;

import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.validation.annotation.Validated;
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
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.common.utils.StringUtils;
import com.ruoyi.system.domain.AiProject;
import com.ruoyi.system.service.IAiProjectService;

/**
 * 项目 信息操作处理
 *
 * <p>桌面端项目工作空间边界。同一项目下的多个会话共享文件工作区，
 * 项目仅对归属用户本人开放(不挂按钮级权限点,由 Controller 内校验 user_id)。
 */
@RestController
@RequestMapping("/ai/project")
public class AiProjectController extends BaseController
{
    @Autowired
    private IAiProjectService aiProjectService;

    /**
     * 查询当前用户的项目列表(带 session_count,按 create_time 倒序)
     */
    @GetMapping("/list")
    public TableDataInfo list(AiProject aiProject)
    {
        aiProject.setUserId(getUserId());
        startPage();
        List<AiProject> list = aiProjectService.selectAiProjectList(aiProject);
        return getDataTable(list);
    }

    /**
     * 查询项目详情
     */
    @GetMapping("/{projectId}")
    public AjaxResult getInfo(@PathVariable Long projectId)
    {
        return success(requireOwned(projectId));
    }

    /**
     * 新建项目
     */
    @Log(title = "AI项目", businessType = BusinessType.INSERT)
    @PostMapping
    public AjaxResult add(@Validated @RequestBody AiProject project)
    {
        if (StringUtils.isEmpty(project.getProjectName()))
        {
            throw new ServiceException("项目名称不能为空");
        }
        project.setUserId(getUserId());
        project.setCreateBy(getUsername());
        return toAjax(aiProjectService.insertAiProject(project));
    }

    /**
     * 修改项目
     */
    @Log(title = "AI项目", businessType = BusinessType.UPDATE)
    @PutMapping("/{projectId}")
    public AjaxResult edit(@PathVariable Long projectId, @Validated @RequestBody AiProject project)
    {
        requireOwned(projectId);
        if (StringUtils.isEmpty(project.getProjectName()))
        {
            throw new ServiceException("项目名称不能为空");
        }
        project.setProjectId(projectId);
        project.setUpdateBy(getUsername());
        return toAjax(aiProjectService.updateAiProject(project));
    }

    /**
     * 删除项目(级联删除其下会话)
     */
    @Log(title = "AI项目", businessType = BusinessType.DELETE)
    @DeleteMapping("/{projectId}")
    public AjaxResult remove(@PathVariable Long projectId)
    {
        requireOwned(projectId);
        return toAjax(aiProjectService.deleteAiProjectById(projectId));
    }

    /** 校验项目存在且属于当前用户,返回项目对象。 */
    private AiProject requireOwned(Long projectId)
    {
        AiProject project = aiProjectService.selectAiProjectById(projectId);
        if (project == null || !java.util.Objects.equals(project.getUserId(), getUserId()))
        {
            throw new ServiceException("项目不存在");
        }
        return project;
    }
}
