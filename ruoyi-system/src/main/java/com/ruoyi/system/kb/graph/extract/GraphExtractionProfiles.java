package com.ruoyi.system.kb.graph.extract;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import com.ruoyi.common.utils.StringUtils;
import com.ruoyi.system.domain.KbKnowledge;

/**
 * 从全局配置 + 知识库字段组装抽取 profile。
 */
@Component
public class GraphExtractionProfiles
{
    @Value("${ai.kb.graph.extract.language:zh}")
    private String language;

    @Value("${ai.kb.graph.extract.domain:}")
    private String domain;

    @Value("${ai.kb.graph.extract.prompt-version:p1}")
    private String promptVersion;

    @Value("${ai.kb.graph.extract.max-gleanings:0}")
    private int maxGleanings;

    @Value("${ai.kb.graph.extract.max-retries:2}")
    private int maxRetries;

    @Value("${ai.kb.graph.extract.retry-base-ms:200}")
    private long retryBaseMs;

    public GraphExtractionProfile forKnowledge(KbKnowledge kb)
    {
        GraphExtractionProfile p = GraphExtractionProfile.defaults();
        p.setLanguage(language);
        p.setDomain(domain);
        p.setPromptVersion(promptVersion);
        p.setMaxGleanings(maxGleanings);
        p.setMaxRetries(maxRetries);
        p.setRetryBaseMs(retryBaseMs);
        if (kb != null && StringUtils.isNotEmpty(kb.getDescription()))
        {
            // 用知识库描述作轻量领域提示，不新增 DB 列
            String d = kb.getDescription().trim();
            if (d.length() > 200)
            {
                d = d.substring(0, 200);
            }
            if (StringUtils.isEmpty(p.getDomain()))
            {
                p.setDomain(d);
            }
        }
        return p;
    }
}
