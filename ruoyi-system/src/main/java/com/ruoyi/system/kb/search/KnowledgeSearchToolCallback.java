package com.ruoyi.system.kb.search;

import java.util.Map;
import java.util.List;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.DefaultToolDefinition;
import org.springframework.ai.tool.definition.ToolDefinition;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.ruoyi.system.kb.KbConstants;
import com.ruoyi.ai.contract.core.InvocationContext;
import com.ruoyi.ai.contract.kb.KnowledgeSearchPort;
import com.ruoyi.ai.contract.kb.SearchHit;
import com.ruoyi.ai.contract.kb.SearchMode;
import com.ruoyi.ai.contract.kb.SearchOptions;
import com.ruoyi.ai.contract.kb.SearchRequest;
import com.ruoyi.ai.contract.kb.SearchResponse;
import com.ruoyi.ai.kb.KnowledgeSearchFormats;
import com.ruoyi.system.tool.UiArtifact;
import com.ruoyi.system.tool.UiArtifactAware;

/**
 * 会话知识检索工具 searchKnowledge。
 * <p>装配期按当前会话选定的 kbIds 动态生成,不进 ai_tool 表(与 drawImage 同模式)。
 * <p>mode 默认 basic（vector 别名）—— 图谱未就绪时行为不变。
 * <p>命中片段通过 {@link UiArtifactAware} 声明 {@code kb.references},给前端引用卡片;
 * 返回给模型的仍是 {@code formatForModel} 文本。
 */
public class KnowledgeSearchToolCallback implements ToolCallback, UiArtifactAware
{
    public static final String TOOL_NAME = "searchKnowledge";

    private static final String INPUT_SCHEMA =
            "{\"$schema\":\"https://json-schema.org/draft/2020-12/schema\",\"type\":\"object\","
          + "\"properties\":{"
          + "\"query\":{\"type\":\"string\",\"description\":\"检索问题或关键词,尽量完整描述需要的信息\"},"
          + "\"topK\":{\"type\":\"integer\",\"description\":\"返回条数,默认5,最大20\"},"
          + "\"mode\":{\"type\":\"string\","
          + "\"enum\":[\"basic\",\"local\",\"hybrid\",\"global\",\"drift\",\"auto\",\"vector\",\"graph\",\"mix\"],"
          + "\"description\":\"检索模式:basic 默认;local/hybrid 图;global 社区;drift 受限追问;auto 规则路由。未就绪时请用 basic\"}"
          + "},\"required\":[\"query\"]}";

    private final List<String> kbIds;
    private final KnowledgeSearchPort searchPort;
    private final InvocationContext invocationContext;
    /** 并行检索各线程独立; RecordingToolCallback 在同线程 take */
    private final ThreadLocal<List<UiArtifact>> lastArtifacts = new ThreadLocal<>();

    public KnowledgeSearchToolCallback(List<String> kbIds, KnowledgeSearchPort searchPort,
                                       InvocationContext invocationContext)
    {
        this.kbIds = kbIds != null ? List.copyOf(kbIds) : List.of();
        this.searchPort = searchPort;
        this.invocationContext = invocationContext != null
                ? invocationContext : InvocationContext.system(null);
    }

    @Override
    public List<UiArtifact> lastArtifacts()
    {
        try
        {
            return lastArtifacts.get();
        }
        finally
        {
            lastArtifacts.remove();
        }
    }

    @Override
    public ToolDefinition getToolDefinition()
    {
        return DefaultToolDefinition.builder()
            .name(TOOL_NAME)
            .description("从当前会话选择的知识库中检索与问题相关的文档片段,返回带出处(文件名+章节路径)的内容。"
                + "当用户问题涉及企业内部文档、规范、手册、已上传资料时调用。"
                + "默认 basic 向量检索;图谱就绪可用 local/hybrid;社区就绪可用 global/drift;"
                + "auto 为规则路由(非默认)。回答时请引用出处编号。")
            .inputSchema(INPUT_SCHEMA)
            .build();
    }

    @Override
    public String call(String toolInput)
    {
        return doCall(toolInput);
    }

    @Override
    public String call(String toolInput, ToolContext toolContext)
    {
        return doCall(toolInput);
    }

    private String doCall(String toolInput)
    {
        lastArtifacts.remove();
        if (kbIds.isEmpty())
        {
            return "当前会话未选择知识库。";
        }
        JSONObject input = parse(toolInput);
        String query = input.getString("query");
        if (query == null || query.isBlank())
        {
            return "缺少 query 参数。";
        }
        int topK = input.getIntValue("topK", KbConstants.DEFAULT_TOP_K);
        if (topK <= 0)
        {
            topK = KbConstants.DEFAULT_TOP_K;
        }
        topK = Math.min(topK, 20);
        SearchMode mode = SearchMode.from(input.getString("mode"));
        SearchResponse response = searchPort.search(new SearchRequest(kbIds, query,
                new SearchOptions(mode, topK, KbConstants.DEFAULT_MIN_SCORE, false, Map.of())),
                invocationContext);
        List<SearchHit> hits = response.hits();
        if (!hits.isEmpty())
        {
            lastArtifacts.set(List.of(UiArtifact.kbReferences(
                    KbReferencesUiPayload.fromContract(query, hits))));
        }
        return KnowledgeSearchFormats.forModel(hits);
    }

    private static JSONObject parse(String toolInput)
    {
        if (toolInput == null || toolInput.isBlank())
        {
            return new JSONObject();
        }
        try
        {
            return JSON.parseObject(toolInput);
        }
        catch (Exception e)
        {
            JSONObject o = new JSONObject();
            o.put("query", toolInput);
            return o;
        }
    }
}
