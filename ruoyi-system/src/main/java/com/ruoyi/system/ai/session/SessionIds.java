package com.ruoyi.system.ai.session;

import java.util.regex.Pattern;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.common.utils.StringUtils;

/**
 * 会话 ID 的格式与强度规则 —— 全系统唯一权威。
 *
 * <p><b>为什么需要这个类</b>:sessionId 由客户端生成(新对话在首轮发送前就要能订阅事件、
 * 传附件,所以 id 必须先于落库存在)。这意味着它是一个<b>客户端可控的主键</b>,
 * 而且会被直接用作磁盘目录名({@code WorkspaceSandbox}、{@code ContextFileStore})。
 * 规则一旦分散在各处各写各的,漏掉一处就是路径穿越或越权。
 *
 * <p><b>两级校验</b>,区分「已存在的会话」和「要新建的会话」:
 * <ul>
 *   <li>{@link #isWellFormed} —— 字符集与长度上限。<b>任何</b>把 sessionId 用于路径拼接
 *       或落库的地方都必须先过这道,是纵深防御的底线。存量数据全部满足,收紧无破坏。</li>
 *   <li>{@link #isStrongForNew} —— 额外要求长度 ≥ {@value #MIN_NEW_LENGTH}。
 *       <b>只在新建会话时要求</b>。</li>
 * </ul>
 *
 * <p><b>为什么新建才要求强度</b>:sessionId 在本系统里不只是标识符,它带有能力凭据的性质 ——
 * 会话尚未落库时,事件订阅无法比对属主(库里查不到属主),只能靠「猜不到 id」来兜底
 * (见 {@code ChatRunService#watchableActiveRun})。id 可预测,这层兜底就没了。
 * 但历史会话的 id 已经写死在库里和磁盘上,不能追溯要求,因此强度只约束新增。
 *
 * <p><b>为什么下限是 32 而不是标准 UUID 的 36</b>:服务端定时任务用的是
 * {@code UUID.randomUUID().toString().replace("-", "")},即 32 位无连字符 hex;
 * 前端用 {@code crypto.randomUUID()},即 36 位标准 UUID。两种格式都是 122 位真随机,
 * 强度一致,下限取 32 才能同时容纳。
 *
 * @author ruoyi
 */
public final class SessionIds
{
    /**
     * 可安全用作目录名与主键的字符集。
     *
     * <p>只允许 {@code [A-Za-z0-9_-]}:不含 {@code .} 和路径分隔符,
     * 从字符集层面就排除了 {@code ../} 形态,而不是依赖后续的 normalize 兜底。
     */
    private static final Pattern WELL_FORMED = Pattern.compile("^[A-Za-z0-9_-]{1,64}$");

    /** 新建会话的 id 最小长度:兼容 32 位无连字符 UUID 与 36 位标准 UUID */
    public static final int MIN_NEW_LENGTH = 32;

    private SessionIds()
    {
    }

    /**
     * 是否可安全用作目录名/主键。
     *
     * <p>所有消费点(路径拼接、落库)的准入线。不含强度要求,因此对存量会话永远为真。
     */
    public static boolean isWellFormed(String sessionId)
    {
        return StringUtils.isNotEmpty(sessionId) && WELL_FORMED.matcher(sessionId.trim()).matches();
    }

    /**
     * 是否满足新建会话的强度要求。
     *
     * <p>比 {@link #isWellFormed} 多一条长度下限 —— 挡住 {@code sessionId=a} 这类
     * 可枚举的弱 id 被写进库里。
     */
    public static boolean isStrongForNew(String sessionId)
    {
        return isWellFormed(sessionId) && sessionId.trim().length() >= MIN_NEW_LENGTH;
    }

    /**
     * 校验格式,不合法抛 {@link ServiceException}。
     *
     * <p>给已存在会话的读写路径用 —— 此时不该再要求强度,会话早就建出来了。
     */
    public static void requireWellFormed(String sessionId)
    {
        if (!isWellFormed(sessionId))
        {
            throw new ServiceException("会话ID格式不正确");
        }
    }

    /**
     * 校验新建会话的 id,不合法抛 {@link ServiceException}。
     *
     * <p>只在「这个 id 在库里还不存在、即将新建一行」的分支调用。
     * 对已存在的会话调用会误伤存量短 id,那是 {@link #requireWellFormed} 的场合。
     */
    public static void requireStrongForNew(String sessionId)
    {
        if (!isWellFormed(sessionId))
        {
            throw new ServiceException("会话ID格式不正确");
        }
        if (sessionId.trim().length() < MIN_NEW_LENGTH)
        {
            throw new ServiceException("会话ID强度不足,至少需要 " + MIN_NEW_LENGTH + " 位随机字符");
        }
    }

    /** 规范化:去空白。落库与拼路径前统一走这里,避免 " abc" / "abc" 被当成两个会话。 */
    public static String normalize(String sessionId)
    {
        return sessionId == null ? null : sessionId.trim();
    }
}
