package com.ruoyi.system.ai;

import java.util.regex.Pattern;

/**
 * OpenAI 兼容渠道端点归一化。
 *
 * <p>各官方/中转渠道的 baseUrl 存在两种风格:
 * ① 官方 OpenAI 风格:baseUrl 不含版本段(如 https://api.openai.com),依赖路径自带宽版本
 *    段 —— Spring AI OpenAiApi 默认就在资源路径前拼 /v1(completionsPath=/v1/chat/completions);
 * ② 版本写死在 baseUrl 里(如 https://ark.cn-beijing.volces.com/api/v3、火山「云上套餐」
 *    https://ark.cn-beijing.volces.com/api/plan/v3、https://api.deepseek.com/v1、
 *    https://qianfan.baidubce.com/v2),此时依赖路径不能再拼版本段,否则拼出
 *    /v3/v1/chat/completions 这种双版本,直接 404。</p>
 *
 * <p>判定规则落在「baseUrl 末段是否已是 vN 版本段」:是 =&gt; 依赖路径不带 /v1 前缀;
 * 否 =&gt; 保留 Spring AI 默认的 /v1 前缀。旧实现 <code>normalizeOpenAiBaseUrl</code>
 * 只剥结尾的 /v1、其余版本段原样保留,再叠默认路径必然双版本,此工具类替代它。</p>
 *
 * @author ruoyi
 */
public final class OpenAiCompatibleEndpoint
{
    /** Spring AI OpenAiApi 默认版本前缀,与 Completion/Eembeddings 默认路径口径一致 */
    static final String DEFAULT_VERSION_PREFIX = "/v1";

    static final String COMPLETIONS = "/chat/completions";
    static final String EMBEDDINGS = "/embeddings";
    static final String IMAGES = "/images/generations";

    /** 版本段:v1 / v2 / v3 / v3dot / v1beta1 等,首字母 v 后紧跟数字 */
    private static final Pattern VERSION_SEGMENT = Pattern.compile("^[vV]\\d[\\w.-]*$");

    private OpenAiCompatibleEndpoint()
    {
    }

    /**
     * 归一化渠道存的原 baseUrl:去掉结尾多余的 /,版本段原样保留(不再摘除)。
     */
    public static String baseUrl(String storedBaseUrl)
    {
        String url = storedBaseUrl == null ? "" : storedBaseUrl.trim();
        while (url.endsWith("/"))
        {
            url = url.substring(0, url.length() - 1);
        }
        return url;
    }

    /**
     * baseUrl 末段是否为 vN 版本段(如 /v1、/v3、/v4、/v1beta)。
     */
    public static boolean versionInBase(String storedBaseUrl)
    {
        String base = baseUrl(storedBaseUrl);
        int idx = base.lastIndexOf('/');
        if (idx < 0)
        {
            return false;
        }
        return VERSION_SEGMENT.matcher(base.substring(idx + 1)).matches();
    }

    /** completionsPath:版本在 baseUrl 里则交给 /chat/completions,否则保留默认 /v1/chat/completions */
    public static String completionsPath(String storedBaseUrl)
    {
        return path(storedBaseUrl, COMPLETIONS);
    }

    /** embeddingsPath:算法同 {@link #completionsPath} */
    public static String embeddingsPath(String storedBaseUrl)
    {
        return path(storedBaseUrl, EMBEDDINGS);
    }

    /** imagesPath:算法同 {@link #completionsPath} */
    public static String imagesPath(String storedBaseUrl)
    {
        return path(storedBaseUrl, IMAGES);
    }

    /**
     * 依赖路径:baseUrl 末段已是版本段 => 剥掉默认 /v1 前缀(直接返回资源路径),
     * 否则返回 /v1 + 资源路径(Spring AI 默认行为)。
     */
    private static String path(String storedBaseUrl, String resourcePath)
    {
        return versionInBase(storedBaseUrl) ? resourcePath : DEFAULT_VERSION_PREFIX + resourcePath;
    }
}