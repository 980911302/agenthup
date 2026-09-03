package com.ruoyi.system.ai;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.system.domain.AiChannel;

/**
 * 上游渠道模型列表客户端
 *
 * 通过渠道的 baseUrl + apiKey 实时查询上游可用模型,用于"导入模型"。
 * Spring AI 未提供模型列表 API,这里基于 Spring RestClient + Jackson 实现,
 * 按渠道类型适配三种协议:
 *   - OPENAI / OLLAMA / 各类 OpenAI 兼容中转: GET {baseUrl}/models (Bearer)
 *   - ANTHROPIC: GET {baseUrl}/models (x-api-key + anthropic-version)
 *   - GEMINI:    GET {baseUrl}/models?key=xxx (name 带 "models/" 前缀,需剥离)
 *
 * @author ruoyi
 */
@Component
public class UpstreamModelClient
{
    private static final Logger log = LoggerFactory.getLogger(UpstreamModelClient.class);

    private static final String ANTHROPIC_VERSION = "2023-06-01";

    private final RestClient restClient;

    private final ObjectMapper objectMapper = new ObjectMapper();

    public UpstreamModelClient()
    {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(5));
        factory.setReadTimeout(Duration.ofSeconds(15));
        this.restClient = RestClient.builder().requestFactory(factory).build();
    }

    /**
     * 查询上游模型列表
     *
     * @param channel 渠道(baseUrl / channelType)
     * @param apiKey  解密后的 apiKey(部分渠道如 Ollama 可空)
     * @return 上游模型列表
     */
    public List<UpstreamModel> fetchModels(AiChannel channel, String apiKey)
    {
        String baseUrl = normalizeBaseUrl(channel.getBaseUrl());
        String type = channel.getChannelType() == null ? "" : channel.getChannelType().trim().toUpperCase();
        try
        {
            if ("ANTHROPIC".equals(type))
            {
                return parseOpenAiShape(doGetAnthropic(baseUrl, apiKey), "anthropic");
            }
            if ("GEMINI".equals(type))
            {
                return parseGeminiShape(doGetGemini(baseUrl, apiKey));
            }
            // OPENAI / OLLAMA 及其他 OpenAI 兼容渠道
            return parseOpenAiShape(doGetOpenAi(baseUrl, apiKey), null);
        }
        catch (ServiceException e)
        {
            throw e;
        }
        catch (RestClientResponseException e)
        {
            log.warn("渠道[{}]查询模型列表失败: HTTP {} {}", channel.getChannelName(), e.getStatusCode(), e.getResponseBodyAsString());
            throw new ServiceException("上游返回 " + e.getStatusCode().value() + ",请检查渠道 baseUrl / apiKey 是否正确");
        }
        catch (RestClientException e)
        {
            log.warn("渠道[{}]连接失败: {}", channel.getChannelName(), e.getMessage());
            throw new ServiceException("无法连接上游渠道:" + e.getMessage());
        }
        catch (Exception e)
        {
            log.warn("渠道[{}]模型列表解析失败: {}", channel.getChannelName(), e.getMessage());
            throw new ServiceException("上游模型列表解析失败:" + e.getMessage());
        }
    }

    /**
     * OpenAI 兼容协议:GET {baseUrl}/models,Authorization: Bearer
     */
    private String doGetOpenAi(String baseUrl, String apiKey)
    {
        return restClient.get()
            .uri(baseUrl + "/models")
            .header("Authorization", "Bearer " + (apiKey == null ? "" : apiKey))
            .retrieve()
            .body(String.class);
    }

    /**
     * Anthropic 协议:GET {baseUrl}/models,x-api-key + anthropic-version
     */
    private String doGetAnthropic(String baseUrl, String apiKey)
    {
        return restClient.get()
            .uri(baseUrl + "/models")
            .header("x-api-key", apiKey == null ? "" : apiKey)
            .header("anthropic-version", ANTHROPIC_VERSION)
            .retrieve()
            .body(String.class);
    }

    /**
     * Gemini 协议:GET {baseUrl}/models?key=xxx,尽量一页拉全
     */
    private String doGetGemini(String baseUrl, String apiKey)
    {
        return restClient.get()
            .uri(baseUrl + "/models?key=" + (apiKey == null ? "" : apiKey) + "&pageSize=1000")
            .retrieve()
            .body(String.class);
    }

    /**
     * 解析 OpenAI / Anthropic 风格响应:{"data":[{"id":"...","owned_by":"..."}]}
     * Anthropic 无 owned_by 字段,归属回退为 defaultOwnedBy
     */
    private List<UpstreamModel> parseOpenAiShape(String body, String defaultOwnedBy) throws Exception
    {
        List<UpstreamModel> result = new ArrayList<>();
        JsonNode data = objectMapper.readTree(body).path("data");
        if (!data.isArray())
        {
            throw new ServiceException("上游响应格式异常:缺少 data 数组");
        }
        for (JsonNode node : data)
        {
            String id = node.path("id").asText("");
            if (id.isEmpty())
            {
                continue;
            }
            String ownedBy = node.path("owned_by").asText("");
            if (ownedBy.isEmpty())
            {
                ownedBy = defaultOwnedBy == null ? "" : defaultOwnedBy;
            }
            String displayName = node.path("display_name").asText("");
            result.add(new UpstreamModel(id, displayName.isEmpty() ? id : displayName, ownedBy,
                    parseInputModalities(node)));
        }
        return result;
    }

    /**
     * 解析 Gemini 风格响应:{"models":[{"name":"models/gemini-2.0-flash","displayName":"..."}]}
     */
    private List<UpstreamModel> parseGeminiShape(String body) throws Exception
    {
        List<UpstreamModel> result = new ArrayList<>();
        JsonNode models = objectMapper.readTree(body).path("models");
        if (!models.isArray())
        {
            throw new ServiceException("上游响应格式异常:缺少 models 数组");
        }
        for (JsonNode node : models)
        {
            String name = node.path("name").asText("");
            if (name.startsWith("models/"))
            {
                name = name.substring("models/".length());
            }
            if (name.isEmpty())
            {
                continue;
            }
            String displayName = node.path("displayName").asText("");
            // Gemini 原生 /models 不给模态信息,留空由导入侧回退推测
            result.add(new UpstreamModel(name, displayName.isEmpty() ? name : displayName, "google", null));
        }
        return result;
    }

    private String normalizeBaseUrl(String baseUrl)
    {
        if (baseUrl == null || baseUrl.trim().isEmpty())
        {
            throw new ServiceException("渠道未配置 baseUrl");
        }
        String url = baseUrl.trim();
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    /**
     * 从上游响应提取输入模态。
     *
     * <p>聚合网关(OpenRouter / OneAPI 等)在 {@code architecture.input_modalities} 里给出
     * 真实模态,官方 OpenAI 的 /models 则没有这个字段 —— 拿不到返回 null,由导入侧回退
     * 按模型名推测。这件事值得做:实测按名字猜的漏报率约三分之二(2026 年多模态已是默认
     * 能力,名字里通常不再带 vl / vision 标记),能拿到真值就不该猜。
     *
     * <p>只保留本系统认识的词元,{@code text} 是所有模型的前提,不入库。
     */
    private static String parseInputModalities(JsonNode node)
    {
        JsonNode mods = node.path("architecture").path("input_modalities");
        if (!mods.isArray() || mods.isEmpty())
        {
            return null;
        }
        List<String> kept = new ArrayList<>();
        for (JsonNode m : mods)
        {
            String v = m.asText("").trim().toLowerCase(Locale.ROOT);
            if (("image".equals(v) || "file".equals(v) || "video".equals(v) || "audio".equals(v))
                    && !kept.contains(v))
            {
                kept.add(v);
            }
        }
        // 数组存在但只有 text:这是明确的"纯文本",与"上游没给信息"不同,返回空串加以区分
        return String.join(",", kept);
    }

    /**
     * 上游模型(自渠道实时拉取,不落库)
     */
    public static class UpstreamModel
    {
        /** 上游模型 ID(API 的 model 参数) */
        private final String id;

        /** 上游展示名(无则同 id) */
        private final String displayName;

        /** 归属方(OpenAI 协议取 owned_by,Anthropic/Gemini 为固定值) */
        private final String ownedBy;

        /** 上游声明的输入模态,逗号分隔;null 表示上游未提供(与空串"纯文本"不同) */
        private final String inputModalities;

        public UpstreamModel(String id, String displayName, String ownedBy, String inputModalities)
        {
            this.id = id;
            this.displayName = displayName;
            this.ownedBy = ownedBy;
            this.inputModalities = inputModalities;
        }

        public String getInputModalities()
        {
            return inputModalities;
        }

        public String getId()
        {
            return id;
        }

        public String getDisplayName()
        {
            return displayName;
        }

        public String getOwnedBy()
        {
            return ownedBy;
        }
    }
}
