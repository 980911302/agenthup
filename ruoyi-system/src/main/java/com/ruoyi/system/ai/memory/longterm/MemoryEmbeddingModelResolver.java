package com.ruoyi.system.ai.memory.longterm;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.ruoyi.common.enums.DataSourceType;
import com.ruoyi.common.datasource.DataSourceScope;
import com.ruoyi.common.utils.StringUtils;
import com.ruoyi.system.service.ISysConfigService;

/**
 * 记忆检索/去重使用的 embedding 模型 code 解析。
 *
 * <p><b>默认跟随知识库的全局配置</b>:平台的向量模型是全局一份,存在 {@code sys_config}
 * 的 {@code kb.default.embeddingModel};知识库建库时由
 * {@code KbKnowledgeServiceImpl.applyPlatformEngineSnapshot} 把它<b>快照固化</b>到
 * {@code kb_knowledge.embedding_model_code}(固化是为了让已有库的向量不因平台改配置而作废,
 * 不代表"每个库各配各的")。记忆没有建库动作,因此直接读全局值。
 *
 * <p>解析顺序:
 * <ol>
 *   <li>{@code ai.memory.embedding-model-code} —— 显式覆盖,留空则跳过。
 *       用于记忆想用比知识库更便宜/更小的模型时开的口子,不填就跟随全局。</li>
 *   <li>{@code sys_config} 的 {@code kb.default.embeddingModel} —— 平台全局向量模型。</li>
 *   <li>都没有 → {@code null},调用方降级(读侧不注入、写侧不做向量去重),对话照常。</li>
 * </ol>
 *
 * <p>带 30s TTL 进程内快照:解析发生在每轮对话的读侧热路径上,不能每轮打一次
 * {@code sys_config}。TTL 与 {@code AgentAssemblyCache} / {@code ToolPolicyService} 同口径 ——
 * 后台直改配置最多等 30s 生效,项目已接受该语义。
 *
 * <p>{@code sys_config} 在 MySQL 主库,而记忆读侧可能跑在 PG 数据源上下文里,
 * 因此取值显式切 MASTER(同 {@code KbKnowledgeServiceImpl} 的做法)。
 *
 * @author ruoyi
 */
@Component
public class MemoryEmbeddingModelResolver
{
    private static final Logger log = LoggerFactory.getLogger(MemoryEmbeddingModelResolver.class);

    /** 知识库平台全局向量模型配置键(见 sql/kb_settings.sql) */
    static final String KB_GLOBAL_EMBEDDING_KEY = "kb.default.embeddingModel";

    /** 与 AgentAssemblyCache / ToolPolicyService 同口径 */
    static long TTL_MS = 30_000L;

    /** 单测可不装配 */
    @Autowired(required = false)
    private ISysConfigService configService;

    /** 显式覆盖;留空 = 跟随知识库全局配置 */
    @Value("${ai.memory.embedding-model-code:}")
    private String override;

    /** 快照:解析结果(可能为 null=无可用模型)与其写入时刻 */
    private volatile String cached;
    private volatile long cachedAt;

    /**
     * 解析当前生效的 embedding 模型 code。
     *
     * @return 模型 code;无可用配置时返回 {@code null}(调用方降级,不抛)
     */
    public String resolve()
    {
        String explicit = StringUtils.trimToNull(override);
        if (explicit != null)
        {
            return explicit;
        }
        long now = System.currentTimeMillis();
        if (now < cachedAt + TTL_MS)
        {
            return cached;
        }
        String global = readGlobal();
        cached = global;
        cachedAt = now;
        return global;
    }

    /** 读平台全局值;失败按「没配」返回 null —— 记忆是旁路,不因配置读失败拖累对话。 */
    private String readGlobal()
    {
        if (configService == null)
        {
            return null;
        }
        try
        {
            return DataSourceScope.runOn(DataSourceType.MASTER,
                    () -> StringUtils.trimToNull(
                            configService.selectConfigByKey(KB_GLOBAL_EMBEDDING_KEY)));
        }
        catch (Exception e)
        {
            log.warn("读取平台向量模型配置失败({}),记忆本次降级为无模型: {}",
                    KB_GLOBAL_EMBEDDING_KEY, e.toString());
            return null;
        }
    }

    /** 单测用:强制下次 resolve 重新读配置 */
    void invalidate()
    {
        cachedAt = 0L;
        cached = null;
    }
}
