package com.ruoyi.system.kb.graph.extract;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
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
import com.ruoyi.system.domain.KbChunk;
import com.ruoyi.system.domain.KbLlmCache;
import com.ruoyi.system.kb.graph.GraphEntity;
import com.ruoyi.system.kb.graph.GraphRelation;
import com.ruoyi.system.kb.graph.GraphSourceIds;
import com.ruoyi.system.kb.graph.KbGraphConstants;
import com.ruoyi.system.kb.graph.extract.ExtractResultParser.ExtractResult;
import com.ruoyi.system.kb.graph.provenance.GraphExtractOutcome;
import com.ruoyi.system.mapper.AiLlmCallMapper;
import com.ruoyi.system.mapper.KbLlmCacheMapper;
import com.ruoyi.system.service.IAiModelService;

/**
 * 单 TextUnit/chunk 实体关系抽取。
 * <p>KB-GR-06：结果显式状态、profile 指纹缓存、仅合法结果入长期缓存、可重试退避。
 */
@Component
public class KbEntityExtractor
{
    private static final Logger log = LoggerFactory.getLogger(KbEntityExtractor.class);

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

    public ExtractResult extract(Long docId, String modelCode, KbChunk chunk, String filePath)
    {
        return extract(docId, modelCode, chunk, filePath, GraphExtractionProfile.defaults());
    }

    public ExtractResult extract(Long docId, String modelCode, KbChunk chunk, String filePath,
                                 GraphExtractionProfile profile)
    {
        if (chunk == null || StringUtils.isEmpty(chunk.getContent()))
        {
            ExtractResult empty = new ExtractResult();
            empty.setOutcome(GraphExtractOutcome.VALID_EMPTY);
            return empty;
        }
        List<Long> sid = chunk.getChunkId() != null ? List.of(chunk.getChunkId()) : List.of();
        return extract(docId, modelCode, chunk.getHeadingPath(), chunk.getContent(), sid, filePath, profile);
    }

    public ExtractResult extract(Long docId, String modelCode, String headingPath, String content,
                                 List<Long> leafChunkIds, String filePath)
    {
        return extract(docId, modelCode, headingPath, content, leafChunkIds, filePath,
            GraphExtractionProfile.defaults());
    }

    /**
     * 从 Graph TextUnit 抽取；sourceIds 使用映射的 LEAF chunkId 列表。
     */
    public ExtractResult extract(Long docId, String modelCode, String headingPath, String content,
                                 List<Long> leafChunkIds, String filePath, GraphExtractionProfile profile)
    {
        long t0 = System.currentTimeMillis();
        GraphExtractionProfile p = profile != null ? profile : GraphExtractionProfile.defaults();
        if (StringUtils.isEmpty(content))
        {
            ExtractResult empty = new ExtractResult();
            empty.setOutcome(GraphExtractOutcome.VALID_EMPTY);
            empty.setElapsedMs(0);
            return empty;
        }

        String system = p.buildSystemPrompt();
        String userMsg = ExtractPrompt.userMessage(headingPath, content);
        String cacheKey = sha256(p.fingerprint() + "\n" + modelCode + "\n" + system + "\n" + userMsg);

        ExtractResult result = null;
        String raw = loadCache(cacheKey);
        boolean fromCache = raw != null;
        int retries = 0;

        if (raw != null)
        {
            result = ExtractResultParser.parse(raw);
            result.setFromCache(true);
            if (GraphExtractOutcome.PARSE_FAILED.equals(result.getOutcome()))
            {
                // 坏缓存不得永久命中：忽略并重调
                fromCache = false;
                raw = null;
                result = null;
            }
        }

        if (raw == null)
        {
            LlmCallOutcome call = callLlmWithRetry(docId, modelCode, system, userMsg, p);
            retries = call.retries;
            raw = call.raw;
            if (call.failed)
            {
                result = new ExtractResult();
                result.setOutcome(GraphExtractOutcome.LLM_FAILED);
                result.setErrorDetail(call.error);
                result.setRetryCount(retries);
                result.setElapsedMs(System.currentTimeMillis() - t0);
                return decorateSources(result, leafChunkIds, filePath);
            }
            result = ExtractResultParser.parse(raw);
            result.setFromCache(false);
            result.setRetryCount(retries);
        }
        if (result == null)
        {
            result = new ExtractResult();
            result.setOutcome(GraphExtractOutcome.LLM_FAILED);
            result.setErrorDetail("empty_result");
            result.setElapsedMs(System.currentTimeMillis() - t0);
            return decorateSources(result, leafChunkIds, filePath);
        }

        if (GraphExtractOutcome.PARSE_FAILED.equals(result.getOutcome()))
        {
            result.setRetryCount(retries);
            result.setElapsedMs(System.currentTimeMillis() - t0);
            return decorateSources(result, leafChunkIds, filePath);
        }

        result = ExtractResultValidator.validate(result, p);

        // Gleaning：默认 0；仅 VALID_EMPTY 且文本够长时最多 1 次
        if (p.getMaxGleanings() > 0
            && GraphExtractOutcome.VALID_EMPTY.equals(result.getOutcome())
            && content.length() >= p.getGleaningMinChars()
            && !fromCache)
        {
            String gleanUser = ExtractPrompt.gleaningUserMessage(headingPath, content);
            LlmCallOutcome call = callLlmWithRetry(docId, modelCode, system, gleanUser, p);
            retries += call.retries + 1;
            if (!call.failed && StringUtils.isNotEmpty(call.raw))
            {
                ExtractResult gleaned = ExtractResultParser.parse(call.raw);
                if (!GraphExtractOutcome.PARSE_FAILED.equals(gleaned.getOutcome()))
                {
                    gleaned = ExtractResultValidator.validate(gleaned, p);
                    if (GraphExtractOutcome.SUCCESS.equals(gleaned.getOutcome()))
                    {
                        result = gleaned;
                        raw = call.raw;
                    }
                }
            }
            result.setRetryCount(retries);
        }

        // 只有验证通过的结果进长期缓存
        if (!fromCache && GraphExtractOutcome.cacheable(result.getOutcome()) && StringUtils.isNotEmpty(raw))
        {
            saveCache(cacheKey, raw, modelCode);
        }

        result.setElapsedMs(System.currentTimeMillis() - t0);
        return decorateSources(result, leafChunkIds, filePath);
    }

    private ExtractResult decorateSources(ExtractResult result, List<Long> leafChunkIds, String filePath)
    {
        List<Long> sid = leafChunkIds != null ? leafChunkIds : List.of();
        List<String> paths = StringUtils.isNotEmpty(filePath) ? List.of(filePath) : List.of();
        for (GraphEntity e : result.getEntities())
        {
            e.setSourceIds(GraphSourceIds.mergeLongs(null, sid, KbGraphConstants.MAX_SOURCE_IDS));
            e.setFilePaths(GraphSourceIds.mergePaths(null, paths, KbGraphConstants.MAX_FILE_PATHS));
        }
        for (GraphRelation r : result.getRelations())
        {
            r.setSourceIds(GraphSourceIds.mergeLongs(null, sid, KbGraphConstants.MAX_SOURCE_IDS));
        }
        return result;
    }

    private LlmCallOutcome callLlmWithRetry(Long docId, String modelCode, String system,
                                            String userMsg, GraphExtractionProfile p)
    {
        int max = p.getMaxRetries();
        int attempt = 0;
        String lastErr = null;
        while (attempt <= max)
        {
            try
            {
                String raw = callLlmOnce(docId, modelCode, system, userMsg);
                if (StringUtils.isNotEmpty(raw))
                {
                    return LlmCallOutcome.ok(raw, attempt);
                }
                lastErr = "empty_response";
                // 空响应视为可重试一次（网络截断）
                if (attempt >= max)
                {
                    break;
                }
            }
            catch (Exception e)
            {
                lastErr = e.getMessage();
                if (!isRetriable(e) || attempt >= max)
                {
                    return LlmCallOutcome.fail(lastErr, attempt);
                }
            }
            sleepBackoff(p.getRetryBaseMs(), attempt);
            attempt++;
        }
        return LlmCallOutcome.fail(lastErr != null ? lastErr : "llm_failed", attempt);
    }

    private String callLlmOnce(Long docId, String modelCode, String system, String userMsg)
    {
        if (StringUtils.isEmpty(modelCode))
        {
            throw new IllegalStateException("未配置 extract_model_code");
        }
        AiModel model = DataSourceScope.runOn(DataSourceType.MASTER,
            () -> aiModelService.selectByModelCode(modelCode));
        if (model == null)
        {
            throw new IllegalStateException("抽取模型不存在: " + modelCode);
        }
        ChatModel chatModel = DataSourceScope.runOn(DataSourceType.MASTER,
            () -> chatModelFactory.get(model.getModelId()));

        String sessionId = KbGraphConstants.LLM_SESSION_PREFIX + docId;
        LlmCallCollector collector = new LlmCallCollector(
            sessionId, null, null, model.getModelId(), modelCode, 0,
            aiLlmCallMapper, tokenEstimator);

        Prompt prompt = new Prompt(List.of(
            new SystemMessage(system),
            new UserMessage(userMsg)));
        ChatResponse response = chatModel.call(prompt);
        collector.onResponse(response);
        collector.onComplete(userMsg, response != null && response.getResult() != null
            ? String.valueOf(response.getResult().getOutput()) : null);

        if (response == null || response.getResult() == null
            || response.getResult().getOutput() == null)
        {
            return null;
        }
        return response.getResult().getOutput().getText();
    }

    static boolean isRetriable(Throwable e)
    {
        if (e == null)
        {
            return false;
        }
        String msg = (e.getClass().getSimpleName() + " " + e.getMessage()).toLowerCase();
        if (msg.contains("timeout") || msg.contains("timed out") || msg.contains("connection")
            || msg.contains("temporarily") || msg.contains("503") || msg.contains("502")
            || msg.contains("429") || msg.contains("reset") || msg.contains("unavailable"))
        {
            return true;
        }
        // 内容/参数类不重试
        if (msg.contains("invalid") || msg.contains("parse") || msg.contains("400")
            || msg.contains("unauthorized") || msg.contains("401") || msg.contains("403"))
        {
            return false;
        }
        // 未知网络异常默认可重试
        return e instanceof java.io.IOException
            || e instanceof java.util.concurrent.TimeoutException
            || (e.getCause() != null && isRetriable(e.getCause()));
    }

    private static void sleepBackoff(long baseMs, int attempt)
    {
        long exp = baseMs * (1L << Math.min(attempt, 4));
        long jitter = ThreadLocalRandom.current().nextLong(0, Math.max(1, baseMs));
        try
        {
            Thread.sleep(exp + jitter);
        }
        catch (InterruptedException ie)
        {
            Thread.currentThread().interrupt();
        }
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
        catch (Exception e)
        {
            log.debug("读 LLM 缓存失败: {}", e.getMessage());
        }
        return null;
    }

    private void saveCache(String key, String response, String modelCode)
    {
        try
        {
            KbLlmCache row = new KbLlmCache();
            row.setCacheKey(key);
            row.setCacheType(KbGraphConstants.CACHE_EXTRACT);
            row.setResponse(response);
            row.setModelCode(modelCode);
            DataSourceScope.runOn(DataSourceType.SLAVE, () -> kbLlmCacheMapper.insert(row));
        }
        catch (Exception e)
        {
            log.debug("写 LLM 缓存失败: {}", e.getMessage());
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

    private static final class LlmCallOutcome
    {
        final String raw;
        final boolean failed;
        final String error;
        final int retries;

        private LlmCallOutcome(String raw, boolean failed, String error, int retries)
        {
            this.raw = raw;
            this.failed = failed;
            this.error = error;
            this.retries = retries;
        }

        static LlmCallOutcome ok(String raw, int retries)
        {
            return new LlmCallOutcome(raw, false, null, retries);
        }

        static LlmCallOutcome fail(String error, int retries)
        {
            return new LlmCallOutcome(null, true, error, retries);
        }
    }
}
