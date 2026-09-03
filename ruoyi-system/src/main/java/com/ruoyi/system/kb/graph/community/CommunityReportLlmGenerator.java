package com.ruoyi.system.kb.graph.community;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.ruoyi.common.datasource.DataSourceScope;
import com.ruoyi.common.enums.DataSourceType;
import com.ruoyi.common.utils.StringUtils;
import com.ruoyi.system.ai.ChatModelFactory;
import com.ruoyi.system.ai.metering.LlmCallCollector;
import com.ruoyi.system.ai.memory.TokenEstimator;
import com.ruoyi.system.domain.AiModel;
import com.ruoyi.system.domain.KbLlmCache;
import com.ruoyi.system.kb.graph.GraphEntity;
import com.ruoyi.system.kb.graph.GraphRelation;
import com.ruoyi.system.kb.graph.KbGraphConstants;
import com.ruoyi.system.kb.graph.community.ExtractiveCommunityReportBuilder.EvidenceSnippet;
import com.ruoyi.system.mapper.AiLlmCallMapper;
import com.ruoyi.system.mapper.KbLlmCacheMapper;
import com.ruoyi.system.service.IAiModelService;

/**
 * 可选 LLM 增强社区报告。默认关闭；失败由上层回退 extractive。
 */
@Component
@ConditionalOnProperty(name = "ai.kb.graph.community.report-use-llm", havingValue = "true")
public class CommunityReportLlmGenerator
{
    private static final Logger log = LoggerFactory.getLogger(CommunityReportLlmGenerator.class);
    public static final String CACHE_TYPE = "community_report";

    @Autowired
    private IAiModelService aiModelService;
    @Autowired
    private ChatModelFactory chatModelFactory;
    @Autowired
    private AiLlmCallMapper aiLlmCallMapper;
    @Autowired
    private TokenEstimator tokenEstimator;
    @Autowired
    private KbLlmCacheMapper kbLlmCacheMapper;

    public CommunityReportDraft enhance(
        CommunityReportDraft base,
        List<GraphEntity> entities,
        List<GraphRelation> relations,
        List<CommunityReportDraft> children,
        List<EvidenceSnippet> evidences,
        String modelCode)
    {
        if (base == null || StringUtils.isEmpty(modelCode))
        {
            return null;
        }
        String entitiesBlock = entities == null ? "" : entities.stream()
            .map(e -> "- " + e.getName()
                + (e.getType() != null ? " (" + e.getType() + ")" : "")
                + (e.getDescription() != null ? ": " + e.getDescription() : ""))
            .limit(30)
            .collect(Collectors.joining("\n"));
        String relationsBlock = relations == null ? "" : relations.stream()
            .map(r -> "- " + r.getSourceName() + " -[" + r.getKeywords() + "]-> " + r.getTargetName())
            .limit(40)
            .collect(Collectors.joining("\n"));
        String childrenBlock = children == null ? "" : children.stream()
            .map(c -> "### " + c.getTitle() + "\n" + c.getSummary())
            .limit(10)
            .collect(Collectors.joining("\n"));
        String evidenceBlock = evidences == null ? "" : evidences.stream()
            .map(e -> e.chunkId() + "|" + (e.text() == null ? "" : e.text()))
            .collect(Collectors.joining("\n"));

        String user = CommunityReportPrompt.buildUserMessage(
            "L" + base.getLevel() + "-" + base.getCommunityId(),
            entitiesBlock, relationsBlock, childrenBlock, evidenceBlock);
        String cacheKey = sha256(modelCode + "\n" + CommunityReportPrompt.PROMPT_VERSION
            + "\n" + CommunityReportPrompt.SYSTEM + "\n" + user
            + "\n" + base.getContentHash());
        String cached = loadCache(cacheKey);
        String text = cached != null ? cached : callLlm(base.getKbId(), modelCode, user, cacheKey);
        if (StringUtils.isEmpty(text))
        {
            return null;
        }
        return parseInto(base, text, modelCode);
    }

    private CommunityReportDraft parseInto(CommunityReportDraft base, String text, String modelCode)
    {
        CommunityReportDraft out = new CommunityReportDraft();
        out.setKbId(base.getKbId());
        out.setGraphVersion(base.getGraphVersion());
        out.setLevel(base.getLevel());
        out.setCommunityId(base.getCommunityId());
        out.setModelCode(modelCode);
        out.setPromptVersion(CommunityReportPrompt.PROMPT_VERSION);
        out.setStatus("READY");
        out.getEntityNames().addAll(base.getEntityNames());
        out.getSourceChunkIds().addAll(base.getSourceChunkIds());

        try
        {
            String json = extractJson(text);
            JSONObject obj = JSON.parseObject(json);
            out.setTitle(obj.getString("title") != null ? obj.getString("title") : base.getTitle());
            out.setSummary(obj.getString("summary") != null ? obj.getString("summary") : base.getSummary());
            JSONArray findings = obj.getJSONArray("findings");
            out.setFindingsJson(findings != null ? findings.toJSONString() : base.getFindingsJson());
            StringBuilder full = new StringBuilder();
            full.append("# ").append(out.getTitle()).append("\n\n");
            full.append(out.getSummary()).append("\n\n");
            if (findings != null)
            {
                full.append("## 关键发现\n");
                for (int i = 0; i < findings.size(); i++)
                {
                    JSONObject f = findings.getJSONObject(i);
                    if (f != null)
                    {
                        full.append("- ").append(f.getString("claim")).append("\n");
                    }
                }
            }
            JSONArray keys = obj.getJSONArray("key_entities");
            if (keys != null && !keys.isEmpty())
            {
                full.append("\n## 关键实体\n");
                for (int i = 0; i < keys.size(); i++)
                {
                    full.append("- ").append(keys.getString(i)).append("\n");
                }
            }
            JSONArray risks = obj.getJSONArray("risks");
            if (risks != null && !risks.isEmpty())
            {
                full.append("\n## 风险/矛盾\n");
                for (int i = 0; i < risks.size(); i++)
                {
                    full.append("- ").append(risks.getString(i)).append("\n");
                }
            }
            out.setFullContent(full.toString());
            out.setContentHash(ExtractiveCommunityReportBuilder.sha256(
                base.getContentHash() + "|" + modelCode + "|" + CommunityReportPrompt.PROMPT_VERSION
                    + "|" + out.getSummary()));
            return out;
        }
        catch (Exception e)
        {
            log.debug("解析社区报告 JSON 失败: {}", e.getMessage());
            // 非 JSON：整段当 summary
            out.setTitle(base.getTitle());
            out.setSummary(text.length() > 400 ? text.substring(0, 400) : text);
            out.setFullContent(text);
            out.setFindingsJson(base.getFindingsJson());
            out.setContentHash(ExtractiveCommunityReportBuilder.sha256(
                base.getContentHash() + "|raw|" + modelCode));
            return out;
        }
    }

    private String callLlm(Long kbId, String modelCode, String user, String cacheKey)
    {
        AiModel model = DataSourceScope.runOn(DataSourceType.MASTER,
            () -> aiModelService.selectByModelCode(modelCode));
        if (model == null)
        {
            return null;
        }
        ChatModel chatModel = DataSourceScope.runOn(DataSourceType.MASTER,
            () -> chatModelFactory.get(model.getModelId()));
        String sessionId = KbGraphConstants.LLM_SESSION_PREFIX + "comm-" + kbId;
        LlmCallCollector collector = new LlmCallCollector(
            sessionId, null, null, model.getModelId(), modelCode, 0,
            aiLlmCallMapper, tokenEstimator);
        Prompt prompt = new Prompt(List.of(
            new SystemMessage(CommunityReportPrompt.SYSTEM),
            new UserMessage(user)));
        ChatResponse response = chatModel.call(prompt);
        collector.onResponse(response);
        String text = response != null && response.getResult() != null
            && response.getResult().getOutput() != null
            ? response.getResult().getOutput().getText() : null;
        collector.onComplete(user, text);
        if (StringUtils.isNotEmpty(text))
        {
            saveCache(cacheKey, text, modelCode);
        }
        return text;
    }

    private String loadCache(String key)
    {
        try
        {
            KbLlmCache hit = DataSourceScope.runOn(DataSourceType.SLAVE,
                () -> kbLlmCacheMapper.selectByKey(key));
            if (hit != null && StringUtils.isNotEmpty(hit.getResponse()))
            {
                DataSourceScope.runOn(DataSourceType.SLAVE,
                    () -> kbLlmCacheMapper.incrementHit(key));
                return hit.getResponse();
            }
        }
        catch (Exception ignored)
        {
        }
        return null;
    }

    private void saveCache(String key, String response, String modelCode)
    {
        try
        {
            KbLlmCache row = new KbLlmCache();
            row.setCacheKey(key);
            row.setCacheType(CACHE_TYPE);
            row.setResponse(response);
            row.setModelCode(modelCode);
            DataSourceScope.runOn(DataSourceType.SLAVE, () -> kbLlmCacheMapper.insert(row));
        }
        catch (Exception ignored)
        {
        }
    }

    private static String extractJson(String text)
    {
        String t = text.trim();
        if (t.startsWith("```"))
        {
            int first = t.indexOf('\n');
            int last = t.lastIndexOf("```");
            if (first > 0 && last > first)
            {
                t = t.substring(first + 1, last).trim();
            }
        }
        int i = t.indexOf('{');
        int j = t.lastIndexOf('}');
        if (i >= 0 && j > i)
        {
            return t.substring(i, j + 1);
        }
        return t;
    }

    private static String sha256(String s)
    {
        try
        {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(s.getBytes(StandardCharsets.UTF_8)));
        }
        catch (Exception e)
        {
            return Integer.toHexString(s.hashCode());
        }
    }
}
