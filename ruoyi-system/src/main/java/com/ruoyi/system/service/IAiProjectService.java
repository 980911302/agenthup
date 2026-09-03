package com.ruoyi.system.service;

import java.util.List;
import com.ruoyi.system.domain.AiProject;

/**
 * 项目 服务层
 * <p>
 * 管理项目生命周期。项目仅对归属用户本人可见;删除项目时级联删除其下会话。
 */
public interface IAiProjectService
{
    /** 查询项目详情(调用方需先校验归属) */
    public AiProject selectAiProjectById(Long projectId);

    /** 查询某用户的全部项目 */
    public List<AiProject> selectAiProjectList(AiProject aiProject);

    /** 新增项目(返回主键) */
    public int insertAiProject(AiProject aiProject);

    /** 修改项目 */
    public int updateAiProject(AiProject aiProject);

    /** 删除项目(级联删除其下会话) */
    public int deleteAiProjectById(Long projectId);
}
