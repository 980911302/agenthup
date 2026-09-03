package com.ruoyi.system.ai.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.system.domain.AiChatRun;
import com.ruoyi.system.domain.AiChatSession;
import com.ruoyi.system.mapper.AiChatRunMapper;
import com.ruoyi.system.mapper.AiChatSessionMapper;

/**
 * 会话准入单测(mock mapper)。
 *
 * <p>锁住越权边界:别人的会话、无主的脏数据行、以及「用 insert ignore 抢注他人 id」
 * 这条最隐蔽的路径 —— 它不会报主键冲突,只会静默 no-op,不重读就会把别人的会话当自己的。
 */
@ExtendWith(MockitoExtension.class)
class SessionAccessGuardTest
{
    private static final Long OWNER = 100L;
    private static final Long ATTACKER = 200L;

    @Mock
    private AiChatSessionMapper sessionMapper;

    @Mock
    private AiChatRunMapper runMapper;

    @InjectMocks
    private SessionAccessGuard guard;

    private static AiChatSession sessionOf(String id, Long userId)
    {
        AiChatSession s = new AiChatSession();
        s.setSessionId(id);
        s.setUserId(userId);
        return s;
    }

    private static String strongId()
    {
        return UUID.randomUUID().toString();
    }

    /* ============ requireOwned ============ */

    @Test
    void 属主可以访问自己的会话()
    {
        String id = strongId();
        AiChatSession mine = sessionOf(id, OWNER);
        when(sessionMapper.selectAiChatSessionById(id)).thenReturn(mine);

        assertSame(mine, guard.requireOwned(id, OWNER, false));
    }

    @Test
    void 他人会话拒绝访问()
    {
        String id = strongId();
        when(sessionMapper.selectAiChatSessionById(id)).thenReturn(sessionOf(id, OWNER));

        assertThrows(ServiceException.class, () -> guard.requireOwned(id, ATTACKER, false));
    }

    @Test
    void 管理员也不能访问他人会话()
    {
        String id = strongId();
        AiChatSession other = sessionOf(id, OWNER);
        when(sessionMapper.selectAiChatSessionById(id)).thenReturn(other);

        assertThrows(ServiceException.class, () -> guard.requireOwned(id, ATTACKER, true));
    }

    @Test
    void 管理员也不能访问他人的运行()
    {
        AiChatRun run = new AiChatRun();
        run.setRunId("run-1");
        run.setUserId(OWNER);

        assertThrows(ServiceException.class, () -> guard.requireOwnedRun(run, ATTACKER, true));
    }

    @Test
    void 无主会话行对任何用户一律拒绝()
    {
        // 历史脏数据:user_id 为 null。若用 Objects.equals(null, null) 判定，
        // 未登录态(userId=null)会意外通过。
        String id = strongId();
        when(sessionMapper.selectAiChatSessionById(id)).thenReturn(sessionOf(id, null));

        assertThrows(ServiceException.class, () -> guard.requireOwned(id, null, false));
        assertThrows(ServiceException.class, () -> guard.requireOwned(id, OWNER, false));
    }

    @Test
    void 不存在与无权的错误消息一致以免成为存在性预言机()
    {
        String missing = strongId();
        String othersId = strongId();
        when(sessionMapper.selectAiChatSessionById(missing)).thenReturn(null);
        when(sessionMapper.selectAiChatSessionById(othersId)).thenReturn(sessionOf(othersId, OWNER));

        String onMissing = assertThrows(ServiceException.class,
                () -> guard.requireOwned(missing, ATTACKER, false)).getMessage();
        String onDenied = assertThrows(ServiceException.class,
                () -> guard.requireOwned(othersId, ATTACKER, false)).getMessage();

        assertEquals(onMissing, onDenied, "两种情况必须同一条消息，否则可用来探测会话是否存在");
    }

    @Test
    void 畸形id在查库前就被拒()
    {
        assertThrows(ServiceException.class, () -> guard.requireOwned("../etc/passwd", OWNER, false));
        verify(sessionMapper, never()).selectAiChatSessionById(any());
    }

    /* ============ requireOwnedIfExists:预订阅口子 ============ */

    @Test
    void 会话不存在时预订阅放行()
    {
        String id = strongId();
        when(sessionMapper.selectAiChatSessionById(id)).thenReturn(null);

        assertNull(guard.requireOwnedIfExists(id, OWNER, false));
    }

    @Test
    void 预订阅不能绕过属主校验()
    {
        // 口子只对「不存在」开放;一旦落库就必须是本人，
        // 否则拿到别人的 id 就能挂上监听收生命周期通知。
        String id = strongId();
        when(sessionMapper.selectAiChatSessionById(id)).thenReturn(sessionOf(id, OWNER));

        assertThrows(ServiceException.class, () -> guard.requireOwnedIfExists(id, ATTACKER, false));
    }

    /* ============ requireOrCreate:建行路径 ============ */

    @Test
    void 新建会话要求强id()
    {
        when(sessionMapper.selectAiChatSessionById("abc")).thenReturn(null);

        ServiceException e = assertThrows(ServiceException.class,
                () -> guard.requireOrCreate("abc", OWNER, "u", false, "t", null));
        assertTrue(e.getMessage().contains("强度"));
        verify(sessionMapper, never()).insertAiChatSessionIfAbsent(any());
    }

    @Test
    void 存量短id的老会话仍可继续使用()
    {
        // 已存在分支不追溯 id 强度，否则历史会话会突然发不了消息
        when(sessionMapper.selectAiChatSessionById("abc")).thenReturn(sessionOf("abc", OWNER));

        assertNotNull(guard.requireOrCreate("abc", OWNER, "u", false, "t", null));
        verify(sessionMapper, never()).insertAiChatSessionIfAbsent(any());
    }

    @Test
    void 抢注他人id时insert被忽略且重读后拒绝()
    {
        // 攻击链:攻击者拿受害者的 sessionId 调建行接口。
        // insert ignore 静默 no-op(不会抛主键冲突)，只有重读再判属主才能发现。
        String victimId = strongId();
        when(sessionMapper.selectAiChatSessionById(victimId))
                .thenReturn(null)                              // 第一次读:攻击者看到「不存在」
                .thenReturn(sessionOf(victimId, OWNER));       // insert ignore 后重读:实际属于受害者

        assertThrows(ServiceException.class,
                () -> guard.requireOrCreate(victimId, ATTACKER, "atk", false, "t", null));
    }

    @Test
    void 正常新建成功并以当前用户为属主()
    {
        String id = strongId();
        when(sessionMapper.selectAiChatSessionById(id))
                .thenReturn(null)
                .thenReturn(sessionOf(id, OWNER));

        AiChatSession created = guard.requireOrCreate(id, OWNER, "u", false, "标题", null);

        assertEquals(OWNER, created.getUserId());
        verify(sessionMapper).insertAiChatSessionIfAbsent(any());
    }
}
