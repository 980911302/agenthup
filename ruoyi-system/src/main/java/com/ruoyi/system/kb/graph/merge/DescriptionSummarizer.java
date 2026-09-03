package com.ruoyi.system.kb.graph.merge;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import com.ruoyi.common.datasource.DataSourceScope;
import com.ruoyi.common.enums.DataSourceType;
import com.ruoyi.common.utils.StringUtils;
import com.ruoyi.system.ai.ChatModelFactory;
import com.ruoyi.system.ai.metering.LlmCallCollector;
import com.ruoyi.system.ai.memory.TokenEstimator;
import com.ruoyi.system.domain.AiModel;
import com.ruoyi.system.domain.KbLlmCache;
import com.ruoyi.system.kb.chunker.TokenCounter;
import com.ruoyi.system.kb.graph.KbGraphConstants;
import com.ruoyi.system.mapper.AiLlmCallMapper;
import com.ruoyi.system.mapper.KbLlmCacheMapper;
import com.ruoyi.system.service.IAiModelService;

/**
 * 描述合并:条数/token 未超阈则直接拼接,否则 map-reduce 摘要。
 * <p>大部分实体只出现两三次,跳过 LLM 是成本关键。
 */
@Component
public class DescriptionSummarizer
{
    private static final Logger log = LoggerFactory.getLogger(DescriptionSummarizer.class);

    private static final String SUMMARY_SYSTEM =
        "你是中文知识图谱的内容编辑。将同一实体的多条描述合并为一段连贯、客观的中文说明（不超过 200 字）。"
            + "要求：第三人称；开头点明实体名称；保留关键事实与限定条件；去掉重复与口语；不要编造原文没有的信息。"
            + "只输出合并后的描述正文，不要标题、列表符号或解释。";

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

    /**
     * 纯函数判定:是否需要调 LLM(单测友好)。
     */
    public static boolean needsLlm(List<String> descriptions)
    {
        if (descriptions == null || descriptions.isEmpty())
        {
            return false;
        }
        List<String> cleaned = clean(descriptions);
        if (cleaned.size() < KbGraphConstants.FORCE_LLM_SUMMARY_ON_MERGE)
        {
            int tokens = 0;
            for (String d : cleaned)
            {
                tokens += TokenCounter.estimate(d);
            }
            return tokens >= KbGraphConstants.SUMMARY_MAX_TOKENS;
        }
        return true;
    }

    public static String joinDirect(List<String> descriptions)
    {
        List<String> cleaned = clean(descriptions);
        if (cleaned.isEmpty())
        {
            return "";
        }
        return String.join("；", cleaned);
    }

    /**
     * 合并描述。LLM 失败时退回拼接,不中断。
     */
    public String summarize(Long docId, String modelCode, String entityName, List<String> descriptions)
    {
        List<String> cleaned = clean(descriptions);
        if (cleaned.isEmpty())
        {
            return "";
        }
        if (!needsLlm(cleaned))
        {
            return joinDirect(cleaned);
        }
        try
        {
            // 简单 map-reduce:先分组拼接,再一次摘要
            String joined = joinDirect(cleaned);
            if (TokenCounter.estimate(joined) > KbGraphConstants.SUMMARY_MAX_TOKENS * 3
                && cleaned.size() > 4)
            {
                List<String> mid = new ArrayList<>();
                int batch = Math.max(2, cleaned.size() / 3);
                for (int i = 0; i < cleaned.size(); i += batch)
                {
                    int end = Math.min(cleaned.size(), i + batch);
                    mid.add(joinDirect(cleaned.subList(i, end)));
                }
                joined = joinDirect(mid);
            }
            String summarized = callSummaryLlm(docId, modelCode, entityName, joined);
            if (StringUtils.isNotEmpty(summarized))
            {
                return summarized.trim();
            }
        }
        catch (Exception e)
        {
            log.warn("描述摘要失败,退回拼接 entity={}: {}", entityName, e.getMessage());
        }
        return joinDirect(cleaned);
    }

    private String callSummaryLlm(Long docId, String modelCode, String entityName, String joined)
    {
        if (StringUtils.isEmpty(modelCode))
        {
            return null;
        }
        String user = "实体名称：" + entityName + "\n\n待合并描述（每行一条）：\n" + joined
            + "\n\n请输出合并后的中文描述。";
        String cacheKey = sha256(modelCode + "\n" + SUMMARY_SYSTEM + "\n" + user);
        String cached = loadCache(cacheKey);
        if (cached != null)
        {
            return cached;
        }

        AiModel model = DataSourceScope.runOn(DataSourceType.MASTER,
            () -> aiModelService.selectByModelCode(modelCode));
        if (model == null)
        {
            return null;
        }
        ChatModel chatModel = DataSourceScope.runOn(DataSourceType.MASTER,
            () -> chatModelFactory.get(model.getModelId()));

        String sessionId = KbGraphConstants.LLM_SESSION_PREFIX + docId;
        LlmCallCollector collector = new LlmCallCollector(
            sessionId, null, null, model.getModelId(), modelCode, 0,
            aiLlmCallMapper, tokenEstimator);

        Prompt prompt = new Prompt(List.of(
            new SystemMessage(SUMMARY_SYSTEM),
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

    private static List<String> clean(List<String> descriptions)
    {
        List<String> out = new ArrayList<>();
        if (descriptions == null)
        {
            return out;
        }
        for (String d : descriptions)
        {
            if (d != null && !d.isBlank())
            {
                String t = d.trim();
                if (!out.contains(t))
                {
                    out.add(t);
                }
            }
        }
        return out;
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
            row.setCacheType(KbGraphConstants.CACHE_SUMMARY);
            row.setResponse(response);
            row.setModelCode(modelCode);
            DataSourceScope.runOn(DataSourceType.SLAVE, () -> kbLlmCacheMapper.insert(row));
        }
        catch (Exception ignored)
        {
        }
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
