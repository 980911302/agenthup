package com.ruoyi.adapter.kb;

import com.ruoyi.ai.contract.core.InvocationContext;
import com.ruoyi.ai.contract.kb.Citation;
import com.ruoyi.ai.contract.kb.KnowledgeSearchPort;
import com.ruoyi.ai.contract.kb.SearchHit;
import com.ruoyi.ai.contract.kb.SearchMode;
import com.ruoyi.ai.contract.kb.SearchRequest;
import com.ruoyi.ai.contract.kb.SearchResponse;
import com.ruoyi.system.kb.vector.KbSearchHit;
import com.ruoyi.system.kb.search.KbSearchMode;
import com.ruoyi.system.kb.search.KbSearchService;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/** 将现有知识库编排服务适配到稳定检索端口。 */
@Component
public class KbKnowledgeSearchAdapter implements KnowledgeSearchPort
{
    private final KbSearchService delegate;

    public KbKnowledgeSearchAdapter(KbSearchService delegate)
    {
        this.delegate = delegate;
    }

    @Override
    public SearchResponse search(SearchRequest request, InvocationContext context)
    {
        List<Long> kbIds = new ArrayList<>();
        for (String value : request.knowledgeBaseIds())
        {
            try
            {
                kbIds.add(Long.valueOf(value));
            }
            catch (NumberFormatException ignored)
            {
                // 当前 DB 适配器只支持数字 ID；其它 ID 由未来 provider 处理。
            }
        }
        SearchMode requested = request.options().mode();
        KbSearchMode legacyMode = KbSearchMode.of(requested.name().toLowerCase());
        List<KbSearchHit> hits = delegate.search(kbIds, request.query(),
                request.options().topK(), request.options().minScore(), legacyMode,
                request.options().includeDebugTrace());
        return new SearchResponse(toContractHits(hits), requested, List.of(), Map.of());
    }

    static List<SearchHit> toContractHits(List<KbSearchHit> hits)
    {
        if (hits == null || hits.isEmpty()) return List.of();
        List<SearchHit> result = new ArrayList<>(hits.size());
        for (KbSearchHit hit : hits)
        {
            if (hit == null) continue;
            Map<String, Object> metadata = new LinkedHashMap<>();
            put(metadata, "docName", hit.getDocName());
            put(metadata, "headingPath", hit.getHeadingPath());
            put(metadata, "channel", hit.getChannel());
            put(metadata, "chunkIndex", hit.getChunkIndex());
            put(metadata, "rankByChannel", hit.getRankByChannel());
            put(metadata, "parentChunkId", string(hit.getParentChunkId()));
            put(metadata, "entityNames", hit.getEntityNames());
            put(metadata, "communityIds", hit.getCommunityIds());
            put(metadata, "debugTrace", hit.getDebugTrace());
            List<Citation> citations = List.of(new Citation(string(hit.getDocId()),
                    hit.getDocName(), hit.getHeadingPath(), null));
            result.add(new SearchHit(string(hit.getKbId()), string(hit.getDocId()),
                    string(hit.getChunkId()), hit.getContent(), hit.getScore(), citations, metadata));
        }
        return List.copyOf(result);
    }

    private static String string(Object value)
    {
        return value == null ? null : String.valueOf(value);
    }

    private static void put(Map<String, Object> target, String key, Object value)
    {
        if (value != null) target.put(key, value);
    }
}
