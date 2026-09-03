package com.ruoyi.system.service.impl;

import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.system.ai.session.SessionAccessGuard;
import com.ruoyi.system.domain.AiProject;
import com.ruoyi.system.mapper.AiProjectMapper;
import com.ruoyi.system.service.IAiChatSessionService;
import com.ruoyi.system.service.IAiProjectService;
import com.ruoyi.system.tool.AiToolProperties;
import com.ruoyi.system.tool.RemoteWorkspaceService;
import com.ruoyi.system.tool.WorkspaceScopeService;

/**
 * 项目 服务层实现
 */
@Service
public class AiProjectServiceImpl implements IAiProjectService
{
    private static final Logger log = LoggerFactory.getLogger(AiProjectServiceImpl.class);

    @Autowired
    private AiProjectMapper aiProjectMapper;

    @Autowired
    private IAiChatSessionService aiChatSessionService;

    @Autowired
    private SessionAccessGuard sessionGuard;

    @Autowired(required = false)
    private WorkspaceScopeService workspaceScopeService;

    @Autowired(required = false)
    private AiToolProperties aiToolProperties;

    @Autowired(required = false)
    private RemoteWorkspaceService remoteWorkspaceService;

    @Override
    public AiProject selectAiProjectById(Long projectId)
    {
        return aiProjectMapper.selectAiProjectById(projectId);
    }

    @Override
    public List<AiProject> selectAiProjectList(AiProject aiProject)
    {
        return aiProjectMapper.selectAiProjectList(aiProject);
    }

    @Override
    public int insertAiProject(AiProject aiProject)
    {
        return aiProjectMapper.insertAiProject(aiProject);
    }

    @Override
    public int updateAiProject(AiProject aiProject)
    {
        return aiProjectMapper.updateAiProject(aiProject);
    }

    /**
     * 删除项目(级联删除其下会话)。
     * <p>先校验项目存在;对项目下每个会话先 requireNoActiveRun 拦截运行中,再复用
     * {@link IAiChatSessionService#deleteAiChatSessionById} 走完整的会话清理链。
     * 归属校验(user_id 匹配)由 Controller 层负责,Service 层不做。
     */
    @Override
    @Transactional
    public int deleteAiProjectById(Long projectId)
    {
        AiProject project = aiProjectMapper.selectAiProjectById(projectId);
        if (project == null)
        {
            throw new ServiceException("项目不存在");
        }
        List<String> sessionIds = aiProjectMapper.selectSessionIdsByProjectId(projectId);
        for (String sessionId : sessionIds)
        {
            sessionGuard.requireNoActiveRun(sessionId);
            aiChatSessionService.deleteAiChatSessionById(sessionId);
        }
        int rows = aiProjectMapper.deleteAiProjectById(projectId);
        scheduleWorkspaceCleanup(projectId);
        return rows;
    }

    private void scheduleWorkspaceCleanup(Long projectId)
    {
        if (workspaceScopeService == null || aiToolProperties == null)
        {
            return;
        }
        Runnable cleanup = () -> {
            try
            {
                workspaceScopeService.deleteProjectWorkspace(aiToolProperties, projectId);
            }
            catch (Exception e)
            {
                // DB 删除已经提交，磁盘清理失败不能反向伪装成事务失败。
                log.warn("删除项目工作区失败 projectId={}: {}", projectId, e.getMessage());
            }
            // 远端模式下同步清理 OPI 上的 project-{id} 目录;跟配置走,失败只记日志。
            if (remoteWorkspaceService != null && remoteWorkspaceService.enabled())
            {
                try
                {
                    remoteWorkspaceService.clear(WorkspaceScopeService.projectWorkspaceKey(projectId));
                    log.info("已删除远端项目工作区: project-{}", projectId);
                }
                catch (Exception e)
                {
                    log.warn("删除远端项目工作区失败 projectId={}: {}", projectId, e.getMessage());
                }
            }
        };
        if (TransactionSynchronizationManager.isSynchronizationActive())
        {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization()
            {
                @Override
                public void afterCommit()
                {
                    cleanup.run();
                }
            });
        }
        else
        {
            cleanup.run();
        }
    }
}
