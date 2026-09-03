package com.ruoyi.system.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.system.ai.session.SessionAccessGuard;
import com.ruoyi.system.domain.AiProject;
import com.ruoyi.system.mapper.AiProjectMapper;
import com.ruoyi.system.service.impl.AiProjectServiceImpl;

/**
 * 项目 Service 单元测试:级联删会话、运行中拦截、项目不存在。
 */
@ExtendWith(MockitoExtension.class)
class AiProjectServiceTest
{
    @Mock private AiProjectMapper aiProjectMapper;
    @Mock private IAiChatSessionService aiChatSessionService;
    @Mock private SessionAccessGuard sessionGuard;

    @InjectMocks private AiProjectServiceImpl service;

    private AiProject project;

    @BeforeEach
    void setUp()
    {
        project = new AiProject();
        project.setProjectId(1L);
        project.setProjectName("测试项目");
        project.setUserId(100L);
    }

    @Test
    void delete_cascadesSessions()
    {
        when(aiProjectMapper.selectAiProjectById(1L)).thenReturn(project);
        when(aiProjectMapper.selectSessionIdsByProjectId(1L)).thenReturn(List.of("s1", "s2"));
        when(aiProjectMapper.deleteAiProjectById(1L)).thenReturn(1);

        int rows = service.deleteAiProjectById(1L);

        verify(aiChatSessionService).deleteAiChatSessionById("s1");
        verify(aiChatSessionService).deleteAiChatSessionById("s2");
        verify(aiProjectMapper).deleteAiProjectById(1L);
        assertEquals(1, rows);
    }

    @Test
    void delete_projectNotExist_throws()
    {
        when(aiProjectMapper.selectAiProjectById(99L)).thenReturn(null);

        ServiceException ex = assertThrows(ServiceException.class,
                () -> service.deleteAiProjectById(99L));

        assertTrue(ex.getMessage().contains("项目不存在"));
    }

    @Test
    void delete_blockedWhenSessionRunning()
    {
        when(aiProjectMapper.selectAiProjectById(1L)).thenReturn(project);
        when(aiProjectMapper.selectSessionIdsByProjectId(1L)).thenReturn(List.of("s1"));
        doThrow(new ServiceException("会话正在执行，请先停止运行再修改"))
                .when(sessionGuard).requireNoActiveRun("s1");

        assertThrows(ServiceException.class,
                () -> service.deleteAiProjectById(1L));

        // 运行中拦截后不删会话也不删项目
        verify(aiProjectMapper, never()).deleteAiProjectById(1L);
    }
}
