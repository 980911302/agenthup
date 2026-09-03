package com.ruoyi.system.ai.session;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import com.ruoyi.common.exception.ServiceException;

/**
 * 会话 ID 规则单测。
 *
 * <p>这里锁住的是安全边界,不是实现细节:
 * <ul>
 *   <li>路径穿越形态必须在字符集这一层就被拒(不能依赖后续 normalize 兜底);</li>
 *   <li>新建会话必须是强随机 —— 会话落库前的事件订阅只能靠「猜不到 id」兜底;</li>
 *   <li>存量短 id 在非新建路径上必须继续可用,否则老会话会突然打不开。</li>
 * </ul>
 */
class SessionIdsTest
{
    /* ============ 字符集:路径穿越与注入形态 ============ */

    @Test
    void 路径穿越形态一律拒绝()
    {
        assertFalse(SessionIds.isWellFormed("../etc/passwd"));
        assertFalse(SessionIds.isWellFormed(".."));
        assertFalse(SessionIds.isWellFormed("a/b"));
        assertFalse(SessionIds.isWellFormed("a\\b"));
        // 含 . 的一律拒:从字符集层面排除相对路径,而不是逐个模式黑名单
        assertFalse(SessionIds.isWellFormed("a.b"));
    }

    @Test
    void 空与控制字符拒绝()
    {
        assertFalse(SessionIds.isWellFormed(null));
        assertFalse(SessionIds.isWellFormed(""));
        assertFalse(SessionIds.isWellFormed("   "));
        assertFalse(SessionIds.isWellFormed("a\0b"));
        assertFalse(SessionIds.isWellFormed("a\nb"));
        assertFalse(SessionIds.isWellFormed("a b"));
    }

    @Test
    void 超长拒绝()
    {
        assertFalse(SessionIds.isWellFormed("a".repeat(65)));
        assertTrue(SessionIds.isWellFormed("a".repeat(64)));
    }

    /* ============ 两级校验:存量兼容 vs 新建强度 ============ */

    @Test
    void 前端标准UUID与服务端无连字符UUID都算强id()
    {
        // 前端 crypto.randomUUID():36 位带连字符
        String web = UUID.randomUUID().toString();
        // 服务端定时任务 AiJobDispatcher.newSessionId():32 位无连字符
        String job = UUID.randomUUID().toString().replace("-", "");

        assertTrue(SessionIds.isStrongForNew(web), "36 位标准 UUID 必须可用于新建");
        assertTrue(SessionIds.isStrongForNew(job), "32 位无连字符 UUID 必须可用于新建");
    }

    @Test
    void 弱id不能用于新建()
    {
        assertFalse(SessionIds.isStrongForNew("a"));
        assertFalse(SessionIds.isStrongForNew("1"));
        assertFalse(SessionIds.isStrongForNew("abc123"));
        // 差一位也不行:边界必须精确,否则「31 位够不够」会变成后人随手放宽的口子
        assertFalse(SessionIds.isStrongForNew("a".repeat(SessionIds.MIN_NEW_LENGTH - 1)));
        assertTrue(SessionIds.isStrongForNew("a".repeat(SessionIds.MIN_NEW_LENGTH)));
    }

    @Test
    void 存量短id在非新建路径上仍然可用()
    {
        // 关键回归:收紧新建强度不能把历史会话打死 ——
        // 老会话的 id 已经写死在库里和磁盘目录名上,读写它们只该过字符集这一关。
        assertTrue(SessionIds.isWellFormed("abc123"));
        assertDoesNotThrow(() -> SessionIds.requireWellFormed("abc123"));
        assertThrows(ServiceException.class, () -> SessionIds.requireStrongForNew("abc123"));
    }

    @Test
    void 新建校验对畸形id先报格式再报强度()
    {
        // 穿越形态不该被报成「强度不足」,那会误导调用方以为加长就能过
        ServiceException e = assertThrows(ServiceException.class,
                () -> SessionIds.requireStrongForNew("../" + "a".repeat(40)));
        assertTrue(e.getMessage().contains("格式"));
    }

    @Test
    void normalize去空白且对null安全()
    {
        assertTrue(SessionIds.isWellFormed("  abc  "));
        org.junit.jupiter.api.Assertions.assertEquals("abc", SessionIds.normalize("  abc  "));
        org.junit.jupiter.api.Assertions.assertNull(SessionIds.normalize(null));
    }
}
