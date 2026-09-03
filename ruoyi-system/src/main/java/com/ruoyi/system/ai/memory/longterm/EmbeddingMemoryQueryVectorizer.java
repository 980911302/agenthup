package com.ruoyi.system.ai.memory.longterm;

import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import com.ruoyi.system.ai.EmbeddingModelFactory;
import com.ruoyi.system.domain.AiModel;
import com.ruoyi.system.service.IAiModelService;

/**
 * {@link MemoryQueryVectorizer} 的生产实现:经 {@link EmbeddingModelFactory} 走远程 embedding。
 *
 * <p>记忆向量与知识库共用同一套 embedding 模型供应:模型 code 由
 * {@link MemoryEmbeddingModelResolver} 解析(默认跟随知识库的平台全局配置
 * {@code sys_config.kb.default.embeddingModel}),再从 {@code ai_model} 解析出模型、
 * 经工厂取 {@link EmbeddingModel}。模型未配置或取不到时抛异常,由 {@link MemoryRetriever}
 * 静默降级 —— 记忆是增强,不是必需(spec §10)。
 */
@Component
public class EmbeddingMemoryQueryVectorizer implements MemoryQueryVectorizer
{
    @Autowired
    private IAiModelService aiModelService;

    @Autowired
    private EmbeddingModelFactory embeddingModelFactory;

    /** 模型 code 解析:显式覆盖 → 知识库平台全局配置 → 无 */
    @Autowired
    private MemoryEmbeddingModelResolver modelResolver;

    @Override
    public float[] vectorize(String text)
    {
        String embeddingModelCode = modelResolver.resolve();
        if (embeddingModelCode == null || embeddingModelCode.isBlank())
        {
            throw new IllegalStateException(
                    "未配置向量模型:请设置 sys_config 的 kb.default.embeddingModel,"
                            + "或用 ai.memory.embedding-model-code 单独指定");
        }
        AiModel model = aiModelService.selectByModelCode(embeddingModelCode);
        if (model == null)
        {
            throw new IllegalStateException("记忆检索 embedding 模型不存在: " + embeddingModelCode);
        }
        EmbeddingModel embeddingModel = embeddingModelFactory.get(model.getModelId());
        // Spring AI 的 embed(String) 单文本重载,返回 float[] 向量(见 KbSearchService:308 同款用法)
        return embeddingModel.embed(text);
    }
}
