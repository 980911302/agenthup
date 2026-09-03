package com.ruoyi.system.ai.memory.longterm;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import com.ruoyi.system.ai.memory.TokenEstimator;
import com.ruoyi.system.domain.AiMemory;
import com.ruoyi.system.mapper.AiMemoryMapper;

/**
 * 读侧:每轮自动检索记忆并组装注入文本(记忆系统 spec §7)。
 *
 * <p>由 {@code ChatTurnRunner} 在组装本轮 user 消息时调用。注入文本<b>只拼进发给模型的那份</b>,
 * 落库存用户原话(§7.1 红线由调用方保证,这里只负责产出注入内容)。
 *
 * <p>流程按 §7.2 五条规则:
 * <ol>
 *   <li>每轮都检索,不做「注入一次」优化(注入不落库,下轮自动消失);</li>
 *   <li>空库短路:缓存 {@code userId → hasMemory} 标志(<b>按 userId 而非 agent</b> —— 分层后
 *       用户层可能有货而该 agent 层为空),空则整条链路跳过,连 embedding 都不发;</li>
 *   <li>短消息不检索:低于 {@code min-query-length} 直接返回空;</li>
 *   <li>相似度阈值优先于 top_k:低于 {@code min-score} 宁可返回空也不硬塞;</li>
 *   <li>注入带边界 {@code <user_memory>} 与来源标注,防止模型当成用户本轮输入。</li>
 * </ol>
 *
 * <p>注入文本不标注层级(§7.2):用户层还是 agent 层是系统存储细节,层级冲突已在检索侧
 * 按「agent 层遮蔽用户层」消解,送到模型面前的是一份已定稿的背景。
 *
 * <p><b>旁路语义(spec §10)</b>:本组件任何环节失败都不许抛异常拖死一轮对话 ——
 * embedding 失败 / 检索失败 / 组装失败全部捕获后安静返回不注入。
 */
@Component
public class MemoryRetriever
{
    private static final Logger log = LoggerFactory.getLogger(MemoryRetriever.class);

    /** 空库短路标志的 TTL,照 {@code AgentAssemblyCache} 的 30s 口径(后台写库最多等这么久生效) */
    static final long HAS_MEMORY_TTL_MS = 30_000L;

    /**
     * 注入文本固定头部:边界 + 来源标注 + <b>使用指令</b>。
     *
     * <p>三件事缺一不可:边界标签让模型分得清这不是用户本轮说的话;来源标注防止它被
     * 当成用户输入;<b>使用指令</b>告诉模型拿它干嘛 —— 光给数据不给指令,相关不相关
     * 全靠模型自己悟。最后那句「不要主动复述」是体验红线:否则模型每轮开口就是
     * 「我记得你在北京工作」,用户会觉得毛骨悚然。
     */
    static final String INJECTION_HEADER =
            "<user_memory>\n关于该用户的已知背景(系统检索提供,非本轮输入)。"
                    + "与本轮相关时自然使用,不相关就忽略;不要主动复述,也不要提及你有记忆。";

    /** 每条注入条目的行前缀 */
    private static final String ENTRY_PREFIX = "- ";

    @Autowired
    private MemoryService memoryService;

    @Autowired
    private MemoryQueryVectorizer vectorizer;

    @Autowired
    private TokenEstimator tokenEstimator;

    @Autowired
    private MemoryInjectionBudget injectionBudget;

    /** 空库探测直连台账 mapper(只读全层计数,不改动批次1的 MemoryService 门面) */
    @Autowired(required = false)
    private AiMemoryMapper memoryMapper;

    /** 记忆读侧总开关。关闭后整条链路直接返回不注入,连空库检查都不做。 */
    @Value("${ai.memory.enabled:true}")
    private boolean enabled;

    @Value("${ai.memory.retrieve.top-k:5}")
    private int topK;

    @Value("${ai.memory.retrieve.min-score:0.75}")
    private double minScore;

    /** 身份短问句的独立阈值；默认低于通用阈值，见 application-ai.yml。 */
    @Value("${ai.memory.retrieve.identity-min-score:0.4}")
    private double identityMinScore;

    @Value("${ai.memory.retrieve.min-query-length:8}")
    private int minQueryLength;

    @Value("${ai.memory.retrieve.max-inject-tokens:500}")
    private int maxInjectTokens;

    /**
     * 单轮检索的硬截止秒数,超时视同「未检索到」。<=0 关闭(退回内联同步调用)。
     *
     * <p>旁路语义(spec §10)靠 try/catch 是接不全的:catch 只挡得住抛出来的异常,
     * 挡不住一个<b>迟迟不返回</b>的调用。检索发生在本轮 user 消息落库<b>之前</b>、
     * 同步跑在 chatRunTaskExecutor 的线程上(core=4),向量渠道半死不活时
     * 几轮就能把整个实例的对话能力堵死 —— 记忆是增强不是必需,宁可不注入。
     *
     * <p>字段默认值与 @Value 兜底保持一致,直接 new 出来时不会静默退回无截止。
     */
    @Value("${ai.memory.retrieve.timeout-seconds:5}")
    private long retrieveTimeoutSeconds = 5;

    /**
     * 检索的执行池:与并行工具、上下文压缩共用一条既有约定
     * ({@code ContextCompactor#callWithTimeout} 同款)。
     * CallerRuns 饱和时退化为内联执行 —— 那时截止失效,等同旧行为,不会更糟。
     */
    @Autowired(required = false)
    @Qualifier("parallelToolTaskExecutor")
    private Executor retrieveExecutor;

    /**
     * 空库短路缓存的条数上限。
     *
     * <p>只有「该用户有记忆」才入缓存(无记忆的不缓存,见 {@link #hasMemory}),所以增长受
     * 活跃且已有记忆的用户数约束,不是全量用户数。但它没有任何被动淘汰:一个用户只要探到过
     * 有记忆就永久留驻,长跑进程里只增不减。加上限 + 惰性清理封住这个口子。
     *
     * <p>取 1 万:单项约几十字节,满载不到 1MB;而清空的代价只是各用户各多探一次
     * {@code select ... limit 1},纯优化结构,清空永远是安全的。
     */
    static int HAS_MEMORY_MAX_ENTRIES = 10_000;

    /** userId → 最近一次「该用户有记忆」的时间戳(空库短路缓存;按 userId 而非 agent) */
    private final ConcurrentHashMap<Long, Long> hasMemoryCache = new ConcurrentHashMap<>();

    /** 命中回写专用 daemon 线程(照 AiJobReconciler:不用 @Scheduled,基础设施行为不进任务界面) */
    private ExecutorService hitExecutor;

    @PostConstruct
    void start()
    {
        hitExecutor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "memory-hit-writer");
            t.setDaemon(true);
            return t;
        });
    }

    @PreDestroy
    void stop()
    {
        if (hitExecutor != null)
        {
            hitExecutor.shutdown();
        }
    }

    /**
     * 检索并组装注入文本。
     *
     * @param userId   会话用户(永远强制,跨用户隔离红线);null 时直接不注入
     * @param agentId  当前 agent(可为 null,按用户层处理)
     * @param userText 本轮用户原话
     * @return 注入结果;无注入时 {@code injectedText} 为空、{@code hitMemoryIds} 为空
     */
    public MemoryInjection retrieve(Long userId, Long agentId, String userText)
    {
        if (!enabled)
        {
            return MemoryInjection.none();
        }
        try
        {
            return retrieveWithDeadline(userId, agentId, userText);
        }
        catch (Throwable e)
        {
            // 记忆对主对话是旁路:任何失败都不许抛出去阻塞对话(embedding/检索/组装都可能炸)
            log.warn("记忆检索注入失败,本轮不注入 userId={} agentId={}: {}",
                    userId, agentId, e.toString());
            return MemoryInjection.none();
        }
    }

    /**
     * 给 {@link #doRetrieve} 套一层硬截止:超时按「没检索到」处理,不拖死这一轮。
     *
     * <p>超时后被放弃的调用在执行池上自然结束(浪费有界),照 {@code ContextCompactor} 的口径。
     * 线程切换是安全的:{@code PgMemoryVectorStore} 自己用 {@code DataSourceScope.runOn}
     * 建数据源作用域,不依赖调用线程上已有的 ThreadLocal。
     */
    private MemoryInjection retrieveWithDeadline(Long userId, Long agentId, String userText)
            throws Exception
    {
        if (retrieveTimeoutSeconds <= 0 || retrieveExecutor == null)
        {
            return doRetrieve(userId, agentId, userText);
        }
        CompletableFuture<MemoryInjection> future = CompletableFuture.supplyAsync(
                () -> doRetrieve(userId, agentId, userText), retrieveExecutor);
        try
        {
            return future.get(retrieveTimeoutSeconds, TimeUnit.SECONDS);
        }
        catch (TimeoutException e)
        {
            future.cancel(true);
            log.warn("记忆检索超时(>{}s),本轮不注入 userId={} agentId={}",
                    retrieveTimeoutSeconds, userId, agentId);
            return MemoryInjection.none();
        }
        catch (ExecutionException e)
        {
            // 还原内联调用的穿透语义:交给外层 catch 按「检索失败不注入」统一处理
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            throw cause instanceof Exception ex ? ex : new IllegalStateException(cause);
        }
    }

    private MemoryInjection doRetrieve(Long userId, Long agentId, String userText)
    {
        // 规则4:短消息通常不检索("嗯""继续""好的"的 embedding 无信息量,检索出的全是噪声)。
        // 但“我是谁/我叫什么”是高价值且语义明确的短问句，不能因长度门槛而失去身份记忆。
        if (userId == null || userText == null
                || (userText.length() < minQueryLength && !isIdentityMemoryQuery(userText)))
        {
            return MemoryInjection.none();
        }

        // 规则2:空库短路 —— 按 userId 缓存(分层后用户层可能有货而该 agent 层为空);
        // 跳过时不发 embedding,这是大多数会话(新用户空库)的主路径
        if (!hasMemory(userId))
        {
            return MemoryInjection.none();
        }

        // 规则1:每轮都检索,query 跟着本轮话题走
        float[] query = vectorizer.vectorize(userText); // 失败抛异常 → 外层捕获静默降级
        double scoreThreshold = isIdentityMemoryQuery(userText) ? identityMinScore : minScore;
        List<AiMemory> memories = memoryService.search(
                MemoryTenant.ofAgent(userId, agentId), query, topK, scoreThreshold);
        if (memories.isEmpty())
        {
            return MemoryInjection.none();
        }

        // 规则5 + §7.3:固定头部 + 逐条 [type] content,再按 token 硬顶截断
        List<String> entries = new ArrayList<>();
        for (AiMemory m : memories)
        {
            entries.add(ENTRY_PREFIX + "[" + typeOf(m) + "] " + contentOf(m));
        }
        int headerTokens = tokenEstimator.estimate(INJECTION_HEADER);
        List<String> kept = injectionBudget.cap(headerTokens, maxInjectTokens, entries);
        if (kept.isEmpty())
        {
            return MemoryInjection.none();
        }

        StringBuilder sb = new StringBuilder(INJECTION_HEADER);
        for (String entry : kept)
        {
            sb.append('\n').append(entry);
        }
        sb.append("\n</user_memory>\n");

        List<Long> hitIds = new ArrayList<>();
        for (int i = 0; i < kept.size(); i++)
        {
            hitIds.add(memories.get(i).getMemoryId());
        }
        MemoryInjection result = new MemoryInjection(Optional.of(sb.toString()), hitIds);
        // §7.3:命中即回写 hit_count / last_hit_time(异步,不阻塞主链路)
        hit(hitIds);
        return result;
    }

    /**
     * 身份记忆短问句白名单。只豁免明确询问自身身份/称呼的表达，不降低全局短消息门槛，
     * 避免“嗯”“好的”等无信息量消息也触发 embedding 调用。
     */
    static boolean isIdentityMemoryQuery(String userText)
    {
        if (userText == null)
        {
            return false;
        }
        String compact = userText.replaceAll("[\\s，。！？、,.!?]", "");
        return compact.contains("我是谁")
                || compact.contains("我叫什么")
                || compact.contains("我的名字")
                || compact.contains("我名字");
    }

    /** 空库短路:userId → 是否有记忆的 30s TTL 快照(照 AgentAssemblyCache)。 */
    private boolean hasMemory(Long userId)
    {
        long now = System.currentTimeMillis();
        Long cached = hasMemoryCache.get(userId);
        if (cached != null && now < cached + HAS_MEMORY_TTL_MS)
        {
            return true;
        }
        boolean has = probeHasMemory(userId);
        if (has)
        {
            capCacheBeforePut(now);
            hasMemoryCache.put(userId, now);
        }
        else
        {
            // 查不到不缓存,直改库后 TTL 内重查即自愈(同 AgentAssemblyCache)
            hasMemoryCache.remove(userId);
        }
        return has;
    }

    /**
     * 入缓存前收口容量:先清过期项,清完仍满则整体清空。
     *
     * <p>不做 LRU:这是个纯优化快照,清空的唯一后果是各用户下次各多探一次库,
     * 为它引入淘汰链表/额外依赖不划算。
     */
    private void capCacheBeforePut(long now)
    {
        if (hasMemoryCache.size() < HAS_MEMORY_MAX_ENTRIES)
        {
            return;
        }
        hasMemoryCache.entrySet().removeIf(e -> now >= e.getValue() + HAS_MEMORY_TTL_MS);
        if (hasMemoryCache.size() >= HAS_MEMORY_MAX_ENTRIES)
        {
            log.info("记忆空库短路缓存达上限 {},整体清空(纯优化结构,后果仅为各用户多探一次库)",
                    HAS_MEMORY_MAX_ENTRIES);
            hasMemoryCache.clear();
        }
    }

    /** 试探该用户是否有任何记忆行(全层,含用户层;active/superseded 都算)。失败按「无」短路,不阻塞对话。 */
    private boolean probeHasMemory(Long userId)
    {
        if (memoryMapper == null)
        {
            return true; // 探测不可用时不短路,放行给正常检索(宁可多打一次,不可漏记忆)
        }
        try
        {
            List<AiMemory> rows = memoryMapper.selectByUser(userId);
            return rows != null && !rows.isEmpty();
        }
        catch (Throwable e)
        {
            log.warn("记忆空库检查失败,按有记忆放行 userId={}: {}", userId, e.toString());
            return true;
        }
    }

    /** 命中回写(异步:daemon 线程,不阻塞主对话链路)。executor 未启动(直构场景)时退化为同步。 */
    private void hit(List<Long> memoryIds)
    {
        if (memoryIds == null || memoryIds.isEmpty())
        {
            return;
        }
        List<Long> ids = new ArrayList<>(memoryIds);
        if (hitExecutor == null)
        {
            for (Long id : ids)
            {
                try
                {
                    memoryService.onHit(id);
                }
                catch (Throwable e)
                {
                    log.debug("记忆命中回写失败 memoryId={}: {}", id, e.toString());
                }
            }
            return;
        }
        try
        {
            hitExecutor.execute(() -> {
                for (Long id : ids)
                {
                    try
                    {
                        memoryService.onHit(id);
                    }
                    catch (Throwable e)
                    {
                        log.debug("记忆命中回写失败 memoryId={}: {}", id, e.toString());
                    }
                }
            });
        }
        catch (Throwable e)
        {
            log.debug("记忆命中回写入队失败,忽略: {}", e.toString());
        }
    }

    private static String typeOf(AiMemory m)
    {
        return m.getType() != null ? m.getType() : "fact";
    }

    private static String contentOf(AiMemory m)
    {
        String c = m.getContent();
        return c != null ? c : "";
    }
}
