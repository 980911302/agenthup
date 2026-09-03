package com.ruoyi.system.mapper;

import java.util.List;
import org.apache.ibatis.annotations.Param;
import com.ruoyi.system.domain.AiProject;

/** 项目表 ai_project 数据访问层 */
public interface AiProjectMapper
{
    /** 查询项目(含归属校验时用 projectId + userId) */
    public AiProject selectAiProjectById(Long projectId);

    /** 查询某用户的全部项目(按 create_time 倒序,带 session_count) */
    public List<AiProject> selectAiProjectList(AiProject aiProject);

    /** 新增项目 */
    public int insertAiProject(AiProject aiProject);

    /** 修改项目(动态 SET) */
    public int updateAiProject(AiProject aiProject);

    /** 删除项目(逻辑删除 del_flag='2') */
    public int deleteAiProjectById(Long projectId);

    /** 查询项目下全部会话ID(级联删除用) */
    public List<String> selectSessionIdsByProjectId(@Param("projectId") Long projectId);
}
