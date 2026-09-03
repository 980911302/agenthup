package com.ruoyi.system.kb.graph.community;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import com.ruoyi.common.datasource.DataSourceScope;
import com.ruoyi.common.enums.DataSourceType;
import com.ruoyi.common.utils.StringUtils;
import com.ruoyi.system.ai.EmbeddingModelFactory;
import com.ruoyi.system.domain.AiModel;
import com.ruoyi.system.domain.KbKnowledge;
import com.ruoyi.system.mapper.KbCommunityVectorMapper;
import com.ruoyi.system.mapper.KbKnowledgeMapper;
import com.ruoyi.system.service.IAiModelService;

/**
 * 社区报告独立向量表 kb_community_vector_{dim}（不占用 kb_chunk）。
 */
@Component
public class CommunityReportVectorStore
{
    private static final Logger log = LoggerFactory.getLogger(CommunityReportVectorStore.class);
    private static final Set<Integer> SUPPORTED_DIMS = Set.of(768, 1024, 1536, 3072);

    @Autowired
    private KbCommunityVectorMapper communityVectorMapper;
    @Autowired
    private KbKnowledgeMapper kbKnowledgeMapper;
    @Autowired
    private IAiModelService aiModelService;
    @Autowired
    private EmbeddingModelFactory embeddingModelFactory;

    public void upsert(Long kbId, Long reportId, String text)
    {
        if (kbId == null || reportId == null || StringUtils.isEmpty(text))
        {
            return;
        }
        float[] emb = embed(kbId, text);
        if (emb == null || emb.length == 0)
        {
            return;
        }
        int dim = emb.length;
        if (!SUPPORTED_DIMS.contains(dim))
        {
            log.debug("社区报告向量维度不支持: {}", dim);
            return;
        }
        Map<String, Object> row = new HashMap<>();
        row.put("reportId", reportId);
        row.put("kbId", kbId);
        row.put("embedding", toVectorLiteral(emb));
        DataSourceScope.runOn(DataSourceType.SLAVE,
            () -> communityVectorMapper.upsert(dim, row));
    }

    public void deleteByKb(Long kbId)
    {
        if (kbId == null)
        {
            return;
        }
        DataSourceScope.runOn(DataSourceType.SLAVE, () -> {
            for (int dim : SUPPORTED_DIMS)
            {
                try
                {
                    communityVectorMapper.deleteByKbId(dim, kbId);
                }
                catch (Exception ignored)
                {
                }
            }
        });
    }

    public List<Map<String, Object>> search(Long kbId, float[] query, int topK)
    {
        if (kbId == null || query == null || query.length == 0 || topK <= 0)
        {
            return List.of();
        }
        int dim = query.length;
        if (!SUPPORTED_DIMS.contains(dim))
        {
            return List.of();
        }
        return DataSourceScope.runOn(DataSourceType.SLAVE,
            () -> communityVectorMapper.search(dim, kbId, toVectorLiteral(query), topK));
    }

    private float[] embed(Long kbId, String text)
    {
        try
        {
            KbKnowledge kb = DataSourceScope.runOn(DataSourceType.SLAVE,
                () -> kbKnowledgeMapper.selectKbKnowledgeById(kbId));
            if (kb == null || StringUtils.isEmpty(kb.getEmbeddingModelCode()))
            {
                return null;
            }
            AiModel model = DataSourceScope.runOn(DataSourceType.MASTER,
                () -> aiModelService.selectByModelCode(kb.getEmbeddingModelCode()));
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
            log.debug("社区报告 embedding 失败: {}", e.getMessage());
            return null;
        }
    }

    private static String toVectorLiteral(float[] v)
    {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < v.length; i++)
        {
            if (i > 0)
            {
                sb.append(',');
            }
            sb.append(v[i]);
        }
        sb.append(']');
        return sb.toString();
    }
}
