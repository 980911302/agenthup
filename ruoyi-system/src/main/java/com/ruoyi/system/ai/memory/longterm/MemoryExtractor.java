package com.ruoyi.system.ai.memory.longterm;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.ruoyi.common.datasource.DataSourceScope;
import com.ruoyi.common.enums.DataSourceType;
import com.ruoyi.common.utils.StringUtils;
import com.ruoyi.system.ai.ChatModelFactory;
import com.ruoyi.system.ai.EmbeddingModelFactory;
import com.ruoyi.system.ai.agent.AgentAssemblyCache;
import com.ruoyi.system.ai.metering.LlmCallCollector;
import com.ruoyi.system.ai.memory.TokenEstimator;
import com.ruoyi.system.domain.AiAgent;
import com.ruoyi.system.domain.AiChatMessage;
import com.ruoyi.system.domain.AiMemory;
import com.ruoyi.system.domain.AiModel;
import com.ruoyi.system.mapper.AiLlmCallMapper;
import com.ruoyi.system.mapper.AiMemoryMapper;
import com.ruoyi.system.service.IAiModelService;

/**
 * 从一段对话历史提炼「值得记住的用户事实」并写入记忆库。
 *
 * <p>压缩搭车与空闲会话兜底扫描共用(压缩搭车由另一个子代理按 §8.1 实现,本类只提供
 * 纯提炼能力,不含位点管理 —— 位点由调用方管)。职责:
 * <ol>
 *   <li>把一段历史渲染成文本,调一次 LLM 出 JSON 数组 {@code [{"content","type","scope"}]};</li>
 *   <li><b>层级判定(§8.3)</b>:只有 LLM 显式输出 {@code scope:"user"} 才升用户层,
 *       判不出/其它值一律落 agent 层(保守方向:错放 agent 层只是少共享,错放用户层污染所有 agent);</li>
 *   <li><b>去重与 supersede(§8.4)</b>:content_hash 精确命中丢弃 → 向量检索该层相关记忆,
 *       相似度超阈值丢弃 → LLM 判定为矛盾/更新则 supersede(只在同层内,§6.3 规则3);</li>
 *   <li>护栏:单次产出上限 {@code max-facts-per-run};LLM 失败/超时/解析失败安静跳过,绝不抛到上层。</li>
 * </ol>
 *
 * <p>记忆全链路对主对话是旁路:本类所有异常都在这层吞掉,调用方收到的是「提炼出几条」,
 * 拿不到任何堆栈,也不会把主对话带崩。
 */
@Component
public class MemoryExtractor
{
    private static final Logger log = LoggerFactory.getLogger(MemoryExtractor.class);

    /** 类型白名单,未知收敛到 fact(与 MemoryServiceImpl.normalizeType 同口径) */
    static final List<String> TYPES = List.of("fact", "preference", "event", "goal", "rule");

    @Autowired
    private MemoryService memoryService;

    @Autowired
    private AiMemoryMapper memoryMapper;

    @Autowired
    private TokenEstimator tokenEstimator;

    @Autowired(required = false)
    private AiLlmCallMapper aiLlmCallMapper;

    /** 解析 agent 的模型(空闲扫描在调度线程,无 AgentContext,直接走装配缓存 + 工厂) */
    @Autowired
    private AgentAssemblyCache assemblyCache;

    @Autowired
    private ChatModelFactory chatModelFactory;

    /** 相似度去重用的 embedding(可选:未配置模型 code 时退化为纯 hash 去重) */
    @Autowired(required = false)
    private EmbeddingModelFactory embeddingModelFactory;

    @Autowired(required = false)
    private IAiModelService aiModelService;

    /** 向量写入:提炼器是写侧负责人,写入的记忆要补向量,后续提炼的相似度去重才能命中它。 */
    @Autowired(required = false)
    private MemoryVectorStore vectorStore;

    /** 测试用:直接注入假 ChatModel,绕过工厂与装配缓存(生产为 null)。 */
    ChatModel chatModelOverride;

    /** 测试用:注入自定义 embedding 函数,模拟向量去重(生产为 null,走真实 embedding 链路)。 */
    interface EmbeddingFn
    {
        float[] apply(String text);
    }

    EmbeddingFn embedFn;

    /** 测试用:塞假 ChatModel,绕过工厂与装配缓存。 */
    void setChatModel(ChatModel chatModel)
    {
        this.chatModelOverride = chatModel;
    }

    /** 测试用:塞自定义 embedding 函数,模拟向量相似度。 */
    void setEmbedFn(EmbeddingFn fn)
    {
        this.embedFn = fn;
    }

    /** 单次提炼产出上限(护栏,spec §8.4)。 */
    @Value("${ai.memory.extract.max-facts-per-run:10}")
    private int maxFactsPerRun;

    /** 相似度高于此阈值视为同一事实,丢弃(去重,spec §8.4)。 */
    @Value("${ai.memory.extract.dedup-threshold:0.92}")
    private double dedupThreshold;

    /** 提炼调用超时秒数;{@code <=0} 关闭(直接同步调用)。 */
    @Value("${ai.memory.extract.timeout-seconds:60}")
    private long timeoutSeconds;

    /**
     * 相似度去重的 embedding 模型解析器,与读侧共用一套(默认跟随知识库的平台全局配置
     * {@code sys_config.kb.default.embeddingModel})。解析不出模型则不启用向量去重,
     * 只剩 hash 精确去重 + LLM 的 supersede 判定。单测可不装配。
     */
    @Autowired(required = false)
    private MemoryEmbeddingModelResolver modelResolver;

    /**
     * 从一段未提炼历史提炼事实并写入记忆库。
     *
     * <p>提炼失败/超时/解析失败一律安静跳过,返回 {@code ExtractResult.skipped()}(attempted=false),
     * 不抛异常 —— 调用方据此<b>不推进位点</b>,下次重试(位点由调用方管理,本方法不写位点)。
     *
     * @param userId           发起用户(永远强制)
     * @param agentId          主 agent(提炼一律记到主 agent 名下,spec §8.5)
     * @param sourceSessionId  来源会话(可溯源)
     * @param messages         待提炼的消息(已按 message_id 升序)
     * @param latestMessageId  这批消息覆盖到的最大 message_id;作为写入记忆的 source_message_id
     * @return {@link ExtractResult}:attempted=false 表示本次未真正提炼(无消息/无模型/LLM 失败),
     *         调用方不应推进位点;attempted=true 表示提炼已执行完,persisted 为实际新增条数。
     */
    public ExtractResult extract(Long userId, Long agentId, String sourceSessionId,
                                 List<AiChatMessage> messages, Long latestMessageId)
    {
        if (userId == null || agentId == null || messages == null || messages.isEmpty())
        {
            return ExtractResult.skipped();
        }
        String history = renderHistory(messages);
        if (history.isBlank())
        {
            return ExtractResult.skipped();
        }
        ChatModel chatModel = resolveChatModel(agentId);
        if (chatModel == null)
        {
            log.debug("记忆提炼无可用模型,跳过 session={} agentId={}", sourceSessionId, agentId);
            return ExtractResult.skipped();
        }
        try
        {
            String raw = callLlm(agentId, sourceSessionId, history, chatModel);
            if (raw == null || raw.isBlank())
            {
                // LLM 调用失败/超时:未真正提炼,不推进位点,下次重试
                return ExtractResult.skipped();
            }
            List<Candidate> candidates = parseCandidates(raw);
            if (candidates == null)
            {
                // LLM 输出畸形:未成功提炼,不推进位点,下次重试
                return ExtractResult.skipped();
            }
            if (candidates.isEmpty())
            {
                return ExtractResult.done(0);
            }
            int persisted = persistCandidates(userId, agentId, sourceSessionId,
                    latestMessageId, candidates, chatModel);
            log.info("记忆提炼完成 session={} 候选{}条 写入{}条 覆盖至messageId={}",
                    sourceSessionId, candidates.size(), persisted, latestMessageId);
            return ExtractResult.done(persisted);
        }
        catch (Exception e)
        {
            // 提炼失败/超时安静跳过:位点不推进,下次重试;绝不能抛到主对话
            log.warn("记忆提炼失败,本次跳过 session={}: {}", sourceSessionId, e.getMessage());
            return ExtractResult.skipped();
        }
    }

    // ==================== LLM 调用 ====================

    /**
     * 调一次 LLM 提炼,带超时。失败/超时返回 null(走「跳过」分支)。
     */
    private String callLlm(Long agentId, String sourceSessionId, String history, ChatModel chatModel)
    {
        // user 只带数据:任务、格式、约束全在 system prompt 里,再说一遍纯属重复,
        // 既费 token 又让「稳定 system + 变化 user」的前缀缓存形状变脏
        String userMsg = "对话历史:\n" + history;
        Prompt prompt = new Prompt(List.of(
                new SystemMessage(SYSTEM_PROMPT),
                new UserMessage(userMsg)));
        ChatResponse response = callWithTimeout(chatModel, prompt, sourceSessionId);
        if (response == null)
        {
            return null;
        }
        recordLlmCall(agentId, sourceSessionId, userMsg, response);
        if (response.getResult() == null || response.getResult().getOutput() == null)
        {
            return null;
        }
        return response.getResult().getOutput().getText();
    }

    /** 提炼调用超时包裹:挂死/异常返回 null(走「跳过」),不拖死调度线程。 */
    private ChatResponse callWithTimeout(ChatModel chatModel, Prompt prompt, String sessionId)
    {
        if (timeoutSeconds <= 0)
        {
            return chatModel.call(prompt);
        }
        CompletableFuture<ChatResponse> future =
                CompletableFuture.supplyAsync(() -> chatModel.call(prompt));
        try
        {
            return future.get(timeoutSeconds, TimeUnit.SECONDS);
        }
        catch (java.util.concurrent.TimeoutException e)
        {
            log.warn("记忆提炼调用超时(>{}s) session={},本次跳过", timeoutSeconds, sessionId);
            return null;
        }
        catch (InterruptedException e)
        {
            Thread.currentThread().interrupt();
            log.warn("记忆提炼调用被中断 session={},本次跳过", sessionId);
            return null;
        }
        catch (java.util.concurrent.ExecutionException e)
        {
            log.warn("记忆提炼调用失败 session={}: {}", sessionId,
                    e.getCause() != null ? e.getCause().getMessage() : e.getMessage());
            return null;
        }
    }

    /**
     * 按主 agent 解析其模型并构造 ChatModel。
     * <p>测试直接注入假 ChatModel(见 {@link #setChatModel});生产走装配缓存 +
     * ChatModelFactory(MASTER)。agent 未配置模型视为无模型可提炼,返回 null。
     */
    private ChatModel resolveChatModel(Long agentId)
    {
        if (chatModelOverride != null)
        {
            return chatModelOverride;
        }
        if (chatModelFactory == null || assemblyCache == null)
        {
            return null;
        }
        try
        {
            return DataSourceScope.runOn(DataSourceType.MASTER, () -> {
                AiAgent agent = assemblyCache.agent(agentId);
                if (agent == null || StringUtils.isBlank(agent.getModelCode()))
                {
                    return null;
                }
                AiModel model = assemblyCache.modelByCode(agent.getModelCode());
                if (model == null)
                {
                    return null;
                }
                return chatModelFactory.get(model.getModelId());
            });
        }
        catch (Exception e)
        {
            log.warn("解析提炼模型失败 agentId={}: {}", agentId, e.getMessage());
            return null;
        }
    }

    /** 提炼调用也走统一计量(旁路里的旁路:计量失败不影响提炼结果)。 */
    private void recordLlmCall(Long agentId, String sourceSessionId, String userMsg, ChatResponse response)
    {
        if (aiLlmCallMapper == null || response == null)
        {
            return;
        }
        try
        {
            Long modelId = resolveModelId(agentId);
            LlmCallCollector collector = new LlmCallCollector(
                    sourceSessionId, agentId, null, modelId, null, 0,
                    aiLlmCallMapper, tokenEstimator);
            collector.onResponse(response);
            collector.onComplete(userMsg,
                    response.getResult() != null && response.getResult().getOutput() != null
                            ? String.valueOf(response.getResult().getOutput()) : null);
        }
        catch (Exception e)
        {
            log.debug("记忆提炼计量失败(不影响提炼): {}", e.getMessage());
        }
    }

    private Long resolveModelId(Long agentId)
    {
        if (assemblyCache == null || agentId == null)
        {
            return null;
        }
        try
        {
            return DataSourceScope.runOn(DataSourceType.MASTER, () -> {
                AiAgent agent = assemblyCache.agent(agentId);
                if (agent == null || StringUtils.isBlank(agent.getModelCode()))
                {
                    return null;
                }
                AiModel model = assemblyCache.modelByCode(agent.getModelCode());
                return model != null ? model.getModelId() : null;
            });
        }
        catch (Exception e)
        {
            return null;
        }
    }

    // ==================== 去重 / supersede / 写入 ====================

    /**
     * 单条候选落库,返回是否真正写入(新增或 supersede)。
     * 按 §8.4 流程:hash 命中 → 丢弃;分层向量检索该层相关 → 相似丢弃;矛盾/更新 → supersede(仅同层)。
     */
    private boolean persistOne(Long userId, Long agentId, String sourceSessionId,
                               Long latestMessageId, Candidate c, ChatModel chatModel)
    {
        // 层级判定(§8.3):只有显式 scope=user 才升用户层,其余一律 agent 层
        boolean userScope = "user".equals(c.scope);
        MemoryTenant tenant = userScope ? MemoryTenant.ofUser(userId) : MemoryTenant.ofAgent(userId, agentId);

        // 1. content_hash 精确命中已有 → 丢弃
        String hash = contentHash(c.content);
        if (hash != null)
        {
            AiMemory hit = memoryMapper.selectByHash(userId, tenant.agentId(), hash);
            if (hit != null)
            {
                log.debug("记忆去重:content_hash 命中已有 memoryId={},丢弃", hit.getMemoryId());
                return false;
            }
        }

        // 2. 向量检索该租户层相关记忆(候选落用户层只跟用户层比,agent 层只跟本 agent 层比)
        List<AiMemory> related = searchRelated(tenant, c);
        if (related.isEmpty())
        {
            return addWithHash(tenant, c, hash, sourceSessionId, latestMessageId);
        }

        // 3. 相似度 > 去重阈值 → 视为同一事实,丢弃
        if (isNearDuplicate(c, related))
        {
            return false;
        }

        // 4. LLM 判定为矛盾/更新 → supersede(只在该层内发生,§6.3 规则3)
        AiMemory toSupersede = decideSupersede(agentId, c, related, chatModel);
        if (toSupersede != null)
        {
            Long newId = memoryService.supersede(tenant, toSupersede.getMemoryId(), c.type,
                    c.content, sourceSessionId, latestMessageId);
            if (newId != null)
            {
                persistHash(tenant, newId, hash);
            }
            return newId != null;
        }
        return addWithHash(tenant, c, hash, sourceSessionId, latestMessageId);
    }

    /**
     * 落库一批<b>已经解析好的</b>事实 —— 压缩搭车路径的入口。
     *
     * <p>存在的意义是<b>让两条写入路径共用同一套落库语义</b>。此前压缩搭车自己调
     * {@code memoryService.add(...)},结果少了三样东西:向量(读侧纯向量检索,没有向量
     * 的台账行永远查不到)、{@code content_hash}(后续提炼认不出重复)、去重与 supersede
     * 判定。修复方式不是给那边补三行,而是让它走这里 —— 两套并行的落库逻辑迟早再次分叉。
     *
     * <p>与 {@link #extract} 的区别仅在于事实的来源:那边自己调 LLM 提炼,这边由调用方
     * (压缩搭车)把 {@code <facts>} 段解析好传进来。落库之后的一切完全一致。
     *
     * @param facts 已解析的事实;{@code scope} 由调用方给,判不出一律传 null(落 agent 层)
     * @return 实际新增条数
     */
    public int persistFacts(Long userId, Long agentId, String sourceSessionId,
                            Long latestMessageId, List<Fact> facts)
    {
        if (userId == null || agentId == null || facts == null || facts.isEmpty())
        {
            return 0;
        }
        List<Candidate> candidates = new ArrayList<>();
        for (Fact f : facts)
        {
            if (f != null && StringUtils.isNotBlank(f.content()))
            {
                candidates.add(new Candidate(f.content(), f.type(), f.scope()));
            }
        }
        if (candidates.isEmpty())
        {
            return 0;
        }
        // supersede 判定要 LLM;取不到就退化为「只做去重、不做覆盖」,不阻塞落库
        ChatModel chatModel = null;
        try
        {
            chatModel = resolveChatModel(agentId);
        }
        catch (Exception e)
        {
            log.debug("落库事实时取不到判定模型,退化为仅去重: {}", e.getMessage());
        }
        return persistCandidates(userId, agentId, sourceSessionId, latestMessageId,
                candidates, chatModel);
    }

    /** 候选逐条落库,受 maxFactsPerRun 限制。单条失败不影响其余条。 */
    private int persistCandidates(Long userId, Long agentId, String sourceSessionId,
                                  Long latestMessageId, List<Candidate> candidates,
                                  ChatModel chatModel)
    {
        int persisted = 0;
        for (Candidate c : candidates)
        {
            if (persisted >= maxFactsPerRun)
            {
                break;
            }
            try
            {
                if (persistOne(userId, agentId, sourceSessionId, latestMessageId, c, chatModel))
                {
                    persisted++;
                }
            }
            catch (Exception e)
            {
                // 单条入库失败不影响其它条;位点照常可推进 —— 已成功的条已写上,
                // 失败的条下次提炼会被 hash/相似度去重兜住,不会产生重复记忆
                log.warn("记忆提炼单条入库失败 session={} content={}: {}",
                        sourceSessionId, abbreviate(c.content, 120), e.getMessage());
            }
        }
        return persisted;
    }

    /**
     * 一条待落库的事实(跨模块传递用)。
     *
     * @param scope LLM 给的层级;非 {@code "user"} 一律落 agent 层(spec §8.3 保守方向)
     */
    public record Fact(String content, String type, String scope) { }

    /** 新增 active 记忆并回填 content_hash(记忆库的 hash 去重依赖此列)。 */
    private boolean addWithHash(MemoryTenant tenant, Candidate c, String hash,
                                String sourceSessionId, Long latestMessageId)
    {
        Long newId = memoryService.add(tenant, c.type, c.content, sourceSessionId, latestMessageId);
        if (newId != null)
        {
            persistHash(tenant, newId, hash);
            persistVector(tenant, newId, c.content);
        }
        return newId != null;
    }

    /** 写入的记忆补向量:后续提炼的相似度去重、读侧检索都依赖向量存在。 */
    private void persistVector(MemoryTenant tenant, Long memoryId, String content)
    {
        if (vectorStore == null)
        {
            return;
        }
        try
        {
            float[] v = embed(content);
            if (v != null && v.length > 0)
            {
                vectorStore.upsert(tenant, memoryId, v);
                persistEmbeddingMetadata(tenant, memoryId, v.length);
            }
        }
        catch (Exception e)
        {
            // 向量写入失败只是失去相似度去重这一层,hash 去重仍在;读侧由检索兜底
            log.debug("记忆向量写入失败 memoryId={}: {}", memoryId, e.getMessage());
        }
    }

    /** 成功写入向量后回填台账元数据，便于运维识别待补向量记录与模型升级迁移。 */
    private void persistEmbeddingMetadata(MemoryTenant tenant, Long memoryId, int embeddingDim)
    {
        if (memoryMapper == null || tenant == null || memoryId == null)
        {
            return;
        }
        try
        {
            AiMemory patch = new AiMemory();
            patch.setMemoryId(memoryId);
            patch.setUserId(tenant.userId());
            patch.setEmbeddingDim(embeddingDim);
            if (modelResolver != null)
            {
                patch.setEmbeddingModel(modelResolver.resolve());
            }
            memoryMapper.update(patch);
        }
        catch (Exception e)
        {
            // 元数据仅用于运维与回填识别，向量已落库时不能因此让主流程失败。
            log.debug("记忆向量元数据回填失败 memoryId={}: {}", memoryId, e.getMessage());
        }
    }

    /** 回填 content_hash(MemoryServiceImpl.add 不写此列,精确去重需要它)。 */
    private void persistHash(MemoryTenant tenant, Long memoryId, String hash)
    {
        if (memoryId == null || hash == null)
        {
            return;
        }
        try
        {
            AiMemory patch = new AiMemory();
            patch.setMemoryId(memoryId);
            patch.setUserId(tenant.userId());
            patch.setContentHash(hash);
            memoryMapper.update(patch);
        }
        catch (Exception e)
        {
            // hash 回填失败只是失去「精确去重」这一层,相似度去重与 supersede 判定仍在
            log.debug("记忆 content_hash 回填失败 memoryId={}: {}", memoryId, e.getMessage());
        }
    }

    /** 检索给定租户层(仅该层)的相关已有记忆。 */
    private List<AiMemory> searchRelated(MemoryTenant tenant, Candidate c)
    {
        try
        {
            float[] query = embed(c.content);
            if (query == null || query.length == 0)
            {
                return List.of();
            }
            List<AiMemory> hits = memoryService.search(tenant, query, 5, 0.0);
            // search 是分层检索,可能混入另一层;去重/覆盖判定必须只在同层内做(§6.3 规则3)
            List<AiMemory> sameLayer = new ArrayList<>();
            for (AiMemory m : hits)
            {
                if (m.getAgentId() != null && m.getAgentId().equals(tenant.agentId()))
                {
                    sameLayer.add(m);
                }
            }
            return sameLayer;
        }
        catch (Exception e)
        {
            // 向量检索失败不算提炼失败 —— 退化为纯 hash 去重 + 直接新增
            log.debug("记忆提炼向量检索失败,退化为直接新增: {}", e.getMessage());
            return List.of();
        }
    }

    /** 相似度去重:任一相关记忆与候选相似度 &gt; 阈值即视为同一事实。 */
    private boolean isNearDuplicate(Candidate c, List<AiMemory> related)
    {
        float[] query = embed(c.content);
        if (query == null || query.length == 0)
        {
            return false;
        }
        for (AiMemory m : related)
        {
            float[] other = embed(m.getContent());
            if (other == null || other.length == 0)
            {
                continue;
            }
            double score = cosine(query, other);
            if (score >= dedupThreshold)
            {
                log.debug("记忆去重:与 memoryId={} 相似度 {:.2f} >= {},丢弃",
                        m.getMemoryId(), score, dedupThreshold);
                return true;
            }
        }
        return false;
    }

    /**
     * 矛盾/更新判定:再调一次 LLM,让它看候选与相关记忆,决定「覆盖哪条 / 不覆盖」。
     *
     * <p>§8.4 要求「提炼器要能看到已有相关记忆才能判断覆盖还是新增」,候选的 scope 已把
     * 层级定死,这里只回答一个问题:<b>f 是不是某条 m 的更新/矛盾</b>。判定失败一律按
     * 「不 supersede」处理:丢一次覆盖机会的代价远小于误覆盖。返回 null 表示新增。
     */
    private AiMemory decideSupersede(Long agentId, Candidate c, List<AiMemory> related, ChatModel chatModel)
    {
        if (related == null || related.isEmpty())
        {
            return null;
        }
        try
        {
            StringBuilder relatedText = new StringBuilder();
            for (int i = 0; i < related.size() && i < 3; i++)
            {
                AiMemory m = related.get(i);
                relatedText.append("- [").append(m.getType()).append("] ")
                        .append(m.getContent()).append("(memoryId=").append(m.getMemoryId()).append(")\n");
            }
            String userMsg = "候选事实:\n- [" + c.type + "] " + c.content + "\n\n"
                    + "已有相关记忆:\n" + relatedText
                    + "\n请判断候选事实是否应覆盖(替代)某条已有记忆。只输出 JSON,不要任何其它文字。";
            Prompt prompt = new Prompt(List.of(
                    new SystemMessage(SUPERSEDE_SYSTEM_PROMPT),
                    new UserMessage(userMsg)));
            ChatResponse response = callWithTimeout(chatModel, prompt, null);
            if (response == null || response.getResult() == null || response.getResult().getOutput() == null)
            {
                return null;
            }
            Long targetId = parseSupersedeTarget(response.getResult().getOutput().getText());
            if (targetId == null)
            {
                return null;
            }
            for (AiMemory m : related)
            {
                if (m.getMemoryId().equals(targetId))
                {
                    return m;
                }
            }
            return null;
        }
        catch (Exception e)
        {
            log.debug("记忆 supersede 判定失败,按新增处理: {}", e.getMessage());
            return null;
        }
    }

    /** 解析 supersede 判定结果:JSON 对象 {"supersede": memoryId} 或 {"supersede": null}。 */
    static Long parseSupersedeTarget(String raw)
    {
        if (raw == null)
        {
            return null;
        }
        String trimmed = raw.trim();
        int start = trimmed.indexOf('{');
        int end = trimmed.lastIndexOf('}');
        if (start >= 0 && end > start)
        {
            trimmed = trimmed.substring(start, end + 1);
        }
        try
        {
            JSONObject obj = JSON.parseObject(trimmed);
            Object v = obj.get("supersede");
            if (v == null)
            {
                return null;
            }
            if (v instanceof Number n)
            {
                return n.longValue();
            }
            String s = String.valueOf(v).trim();
            return s.isEmpty() || "null".equalsIgnoreCase(s) ? null : Long.valueOf(s);
        }
        catch (Exception e)
        {
            return null;
        }
    }

    /**
     * 解析 LLM 返回的 facts JSON 数组。
     *
     * <p>返回 {@code null} 表示输出畸形(没有合法 JSON 数组或解析失败)—— 调用方应视为
     * 「提炼没成功」,不推进位点;返回空列表表示合法但没提炼出事实(如 {@code []}),
     * 调用方可推进位点。
     */
    static List<Candidate> parseCandidates(String raw)
    {
        if (raw == null)
        {
            return null;
        }
        String trimmed = raw.trim();
        int start = trimmed.indexOf('[');
        int end = trimmed.lastIndexOf(']');
        if (start < 0 || end <= start)
        {
            return null;
        }
        trimmed = trimmed.substring(start, end + 1);
        List<Candidate> out = new ArrayList<>();
        try
        {
            JSONArray arr = JSON.parseArray(trimmed);
            if (arr == null)
            {
                return null;
            }
            for (int i = 0; i < arr.size(); i++)
            {
                JSONObject o = arr.getJSONObject(i);
                if (o == null)
                {
                    continue;
                }
                String content = o.getString("content");
                if (content == null || content.isBlank())
                {
                    continue;
                }
                String type = o.getString("type");
                if (type == null || !TYPES.contains(type))
                {
                    type = "fact";
                }
                String scope = o.getString("scope");
                out.add(new Candidate(content.trim(), type, scope));
            }
        }
        catch (Exception e)
        {
            return null;
        }
        return out;
    }

    // ==================== 文本 / 向量工具 ====================

    private String renderHistory(List<AiChatMessage> messages)
    {
        StringBuilder sb = new StringBuilder();
        for (AiChatMessage m : messages)
        {
            String type = m.getMessageType();
            if (type == null || "SUMMARY".equals(type))
            {
                continue;
            }
            if ("USER".equals(type))
            {
                sb.append("[用户] ").append(abbreviate(m.getContent(), 2000)).append('\n');
            }
            else if ("ASSISTANT".equals(type))
            {
                sb.append("[助手] ").append(abbreviate(m.getContent(), 2000)).append('\n');
            }
            else if ("TOOL".equals(type))
            {
                sb.append("[工具] ").append(m.getToolName() != null ? m.getToolName() : "?").append('\n');
            }
            // THINKING / SYSTEM 不进提炼视野
        }
        return sb.toString();
    }

    /**
     * 候选正文的 embedding。未配置 embedding 模型 code 或失败返回 null ——
     * 此时去重退化为纯 hash + supersede 判定,不阻塞提炼主流程。
     */
    private float[] embed(String text)
    {
        if (embedFn != null)
        {
            return embedFn.apply(text);
        }
        String embeddingModelCode = modelResolver != null ? modelResolver.resolve() : null;
        if (embeddingModelFactory == null || aiModelService == null
                || StringUtils.isBlank(embeddingModelCode) || text == null)
        {
            return null;
        }
        try
        {
            AiModel model = DataSourceScope.runOn(DataSourceType.MASTER,
                    () -> aiModelService.selectByModelCode(embeddingModelCode));
            if (model == null)
            {
                return null;
            }
            EmbeddingModel emb = DataSourceScope.runOn(DataSourceType.MASTER,
                    () -> embeddingModelFactory.get(model.getModelId()));
            return emb.embed(text);
        }
        catch (Exception e)
        {
            log.debug("记忆提炼 embedding 失败(本次去重退化为纯 hash): {}", e.getMessage());
            return null;
        }
    }

    private static double cosine(float[] a, float[] b)
    {
        if (a == null || b == null || a.length == 0 || a.length != b.length)
        {
            return 0.0;
        }
        double dot = 0;
        double na = 0;
        double nb = 0;
        for (int i = 0; i < a.length; i++)
        {
            dot += (double) a[i] * b[i];
            na += (double) a[i] * a[i];
            nb += (double) b[i] * b[i];
        }
        if (na == 0 || nb == 0)
        {
            return 0.0;
        }
        return dot / (Math.sqrt(na) * Math.sqrt(nb));
    }

    /** 正文归一化后 SHA-256(去空白/标点)。 */
    static String contentHash(String content)
    {
        if (content == null)
        {
            return null;
        }
        String normalized = normalize(content);
        if (normalized.isEmpty())
        {
            return null;
        }
        try
        {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(normalized.getBytes(StandardCharsets.UTF_8)));
        }
        catch (Exception e)
        {
            return Integer.toHexString(normalized.hashCode());
        }
    }

    /** 归一化:去掉空白与中文/英文标点,只留文字与数字,用于精确去重。 */
    static String normalize(String s)
    {
        if (s == null)
        {
            return "";
        }
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++)
        {
            char ch = s.charAt(i);
            if (Character.isWhitespace(ch) || isPunctuation(ch))
            {
                continue;
            }
            sb.append(Character.toLowerCase(ch));
        }
        return sb.toString();
    }

    private static boolean isPunctuation(char ch)
    {
        return "，。！？；：、,.!?;:'\"“”‘’()（）[]{}-—_…《》<>/\\.|@#$%^&*+=".indexOf(ch) >= 0;
    }

    private static String abbreviate(String s, int max)
    {
        if (s == null)
        {
            return "";
        }
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }

    /** 一条候选事实。scope 为 LLM 输出,非 "user" 一律落 agent 层。 */
    static final class Candidate
    {
        final String content;
        final String type;
        final String scope;

        Candidate(String content, String type, String scope)
        {
            this.content = content;
            this.type = type;
            this.scope = scope;
        }
    }

    /**
     * 一次提炼的结果。attempted=false(跳过)时调用方<b>不得推进位点</b> ——
     * LLM 失败/超时/无模型都不算提炼过,下次重试;attempted=true 表示已执行完。
     */
    public record ExtractResult(boolean attempted, int persisted)
    {
        /** 本次未真正提炼(无消息/无模型/LLM 失败/超时)。 */
        public static ExtractResult skipped()
        {
            return new ExtractResult(false, 0);
        }

        /** 本次已提炼完成,写入(新增或 supersede)了 persisted 条。 */
        public static ExtractResult done(int persisted)
        {
            return new ExtractResult(true, persisted);
        }
    }

    // ==================== prompt 常量 ====================

    static final String SYSTEM_PROMPT = """
            你是记忆提炼器。从对话历史中提炼「换一个会话仍然有用」的用户信息,
            这些内容会在后续对话里自动注入。

            必须提炼:用户明说要记住的话("记住…""以后都…""下次别…""请一直…")。
            不要提炼:寒暄与语气词、只在本轮有效的临时上下文、你自己给出的答案或知识
            (助手说过的事实不是用户的事实)、历史里没有的信息。

            type 取值:
              fact=用户或世界的稳定客观陈述;preference=喜好、风格、约束;
              event=已发生的具体事件;goal=进行中的事;rule=做事指令。

            scope 取值,默认 "agent",只有一种情况填 "user":
              这条只关于用户本人、换任何一个智能体都同样成立
              (如"用户在北京工作""用户喜欢简洁的回答")。
              指向某个智能体、只在某个智能体语境下成立、或你拿不准 —— 都填 "agent"。

            只输出 JSON 数组,不要解释或代码块;无可提炼时输出 []:
            [{"content":"…","type":"fact","scope":"user"}]""";

    static final String SUPERSEDE_SYSTEM_PROMPT = """
            你是记忆更新判定器。给你一条「候选事实」和若干「已有记忆」,判断候选事实是否应
            「覆盖(替代)」其中某条已有记忆。
            - 只有当候选事实与某条已有记忆描述同一件事、且候选是更新版本(如"以前喜欢 X,现在喜欢 Y")
              或明显矛盾时,才输出该条已有记忆的 memoryId。
            - 不同的事实并存(如"去过北京"与"去过上海"是两件事),不要覆盖。
            - 不覆盖时输出 {"supersede": null}。
            只输出 JSON,不要任何其它文字。""";
}
