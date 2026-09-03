package com.ruoyi.system.ai.metering;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ConcurrentMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeFilterFunction;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * 从上游 SSE 原文截获 OpenAI 标准缓存字段,按 response id 暂存,
 * 供 {@link LlmCallCollector} 在落库时取走。
 *
 * <p><b>为什么必须回到 HTTP 层</b>:Spring AI 的 {@code UsageCalculator#getCumulativeUsage}
 * 在工具循环里会用 3 参 {@code new DefaultUsage(p,c,t)} 重建 Usage,丢弃 nativeUsage。
 * 从第 2 次调用起 {@code usage.getNativeUsage()} 恒为 null,collector 读不到
 * {@code prompt_tokens_details.cached_tokens}。HTTP 层看到的是每次真实请求的原始响应,不受影响。
 *
 * <p>抓的是中转归一化后的 OpenAI 标准字段 {@code prompt_tokens_details.cached_tokens}
 * (不是 DeepSeek 私有的 {@code prompt_cache_hit_tokens})。miss = prompt_tokens - cached。
 *
 * <p><b>覆盖范围</b>:只挂了 {@code webClientBuilder}(流式路径)。本项目对话全部走
 * {@code .stream()},非流式 RestClient 不在范围内。探针是单例、跨渠道共用 —
 * 不同上游 response id 碰撞概率极低,接受。
 *
 * <p><b>已知限制</b>:{@code response_id} 不保证唯一(collector 轮次切分与上游边界偶发不对齐),
 * 极少数行缓存数据可能张冠李戴;不在本类解决。
 *
 * @author ruoyi
 */
@Component
public class CacheUsageProbe
{
    private static final Logger log = LoggerFactory.getLogger(CacheUsageProbe.class);

    /** 防止上游不返回 id 时 pending 缓慢泄漏 */
    private static final int MAX_PENDING = 1000;

    private static final Pattern ID_PATTERN =
            Pattern.compile("\"id\"\\s*:\\s*\"([^\"]+)\"");
    private static final Pattern PROMPT_TOKENS_PATTERN =
            Pattern.compile("\"prompt_tokens\"\\s*:\\s*(\\d+)");
    private static final Pattern CACHED_TOKENS_PATTERN =
            Pattern.compile("\"cached_tokens\"\\s*:\\s*(\\d+)");

    /** key = 上游 response id, value = [hit, miss] */
    private final ConcurrentMap<String, int[]> pending = new ConcurrentHashMap<>();
    /** 插入顺序,用于超限时淘汰最旧条目 */
    private final ConcurrentLinkedQueue<String> order = new ConcurrentLinkedQueue<>();

    public void record(String responseId, int hit, int miss)
    {
        if (responseId == null || responseId.isEmpty())
        {
            return;
        }
        while (pending.size() >= MAX_PENDING)
        {
            String oldest = order.poll();
            if (oldest == null)
            {
                break;
            }
            pending.remove(oldest);
        }
        // 同一 responseId 重复下发 usage 时只入队一次,否则 order 无界增长
        // —— 淘汰只看 pending.size(),永远轮不到它
        if (pending.put(responseId, new int[] { hit, miss }) == null)
        {
            order.offer(responseId);
        }
    }

    /**
     * 取走即删,避免泄漏。取不到返回 null。
     * <p>调用方须用<strong>全长</strong> responseId(与 SSE 原文一致)。
     * {@code LlmCallCollector} 落库时会 truncate 到 64 字符,但 take 在 truncate 之前,
     * 顺序不要调换。
     */
    public int[] take(String responseId)
    {
        if (responseId == null || responseId.isEmpty())
        {
            return null;
        }
        int[] val = pending.remove(responseId);
        if (val != null)
        {
            order.remove(responseId);
        }
        return val;
    }

    /**
     * 挂到 {@code OpenAiApi.Builder#webClientBuilder} 上的过滤器:
     * 流式 SSE 经过时扫描含缓存字段的 chunk,按 id 投递。
     * 解析异常全部吞掉 —— 这是可选观测数据,绝不能影响对话。
     */
    public WebClient.Builder decorate(WebClient.Builder builder)
    {
        return builder.filter(sseCacheFilter());
    }

    private ExchangeFilterFunction sseCacheFilter()
    {
        return (request, next) -> next.exchange(request).map(this::tapBody);
    }

    private ClientResponse tapBody(ClientResponse response)
    {
        // 每个连接独立行缓冲,避免跨请求串线
        StringBuilder lineBuf = new StringBuilder();
        // 同一条 SSE 流上的 id 是稳定的;带 usage 的末包可能省略 id,
        // 所以记住流上最近一次见到的 id,而不是要求 usage 那一行自带。
        String[] lastId = new String[1];
        // 必须用 mutate().body(Function) 变换原 body —— 若先 bodyToFlux() 再
        // mutate().body(Flux),mutate 内部会再 bodyToFlux 一次,底层
        // ClientHttpResponse 只允许订阅一次,触发
        // "The client response body can only be consumed once"。
        return response.mutate()
                .body(flux -> flux.doOnNext(db -> {
                    try
                    {
                        // 仅用于扫 ASCII 字段名/数字。多字节字符跨 chunk 可能乱码,
                        // 不可把本方法当通用 JSON 解析;DataBuffer.toString 不推进读指针
                        // (DefaultDataBuffer / NettyDataBuffer 均如此),下游解码链仍能读完整 body。
                        String chunk = db.toString(StandardCharsets.UTF_8);
                        lineBuf.append(chunk);
                        int nl;
                        while ((nl = indexOfNewline(lineBuf)) >= 0)
                        {
                            String line = lineBuf.substring(0, nl).trim();
                            lineBuf.delete(0, nl + 1);
                            // 兼容 \r\n:trim 已去掉 \r
                            parseSseLine(line, lastId);
                        }
                    }
                    catch (Exception e)
                    {
                        log.debug("缓存 usage 扫描失败: {}", e.getMessage());
                    }
                }))
                .build();
    }

    private static int indexOfNewline(StringBuilder sb)
    {
        for (int i = 0; i < sb.length(); i++)
        {
            if (sb.charAt(i) == '\n')
            {
                return i;
            }
        }
        return -1;
    }

    private void parseSseLine(String line, String[] lastId)
    {
        if (line == null || line.isEmpty() || "[DONE]".equals(line))
        {
            return;
        }
        // SSE 数据行: data: {...}  或裸 JSON
        String json = line;
        if (line.startsWith("data:"))
        {
            json = line.substring(5).trim();
        }
        if (json.isEmpty() || "[DONE]".equals(json))
        {
            return;
        }
        try
        {
            // 任意 chunk 都可能带 id,先记住,供末包 usage 省略 id 时回落
            Matcher idM = ID_PATTERN.matcher(json);
            if (idM.find())
            {
                lastId[0] = idM.group(1);
            }
            // 只关心带 prompt_tokens_details.cached_tokens 的 usage 包;
            // 中间 chunk 常见 "usage":null,不含这些字段。
            if (!json.contains("cached_tokens") || !json.contains("prompt_tokens"))
            {
                return;
            }
            Matcher promptM = PROMPT_TOKENS_PATTERN.matcher(json);
            Matcher cachedM = CACHED_TOKENS_PATTERN.matcher(json);
            if (!promptM.find() || !cachedM.find())
            {
                return;
            }
            int prompt = Integer.parseInt(promptM.group(1));
            int hit = Integer.parseInt(cachedM.group(1));
            int miss = Math.max(0, prompt - hit);
            String responseId = lastId[0];
            if (responseId == null || responseId.isEmpty())
            {
                // 整条流都没见过 id,无法与 collector 对齐
                return;
            }
            record(responseId, hit, miss);
        }
        catch (Exception e)
        {
            log.debug("解析 cached_tokens 失败: {}", e.getMessage());
        }
    }
}
