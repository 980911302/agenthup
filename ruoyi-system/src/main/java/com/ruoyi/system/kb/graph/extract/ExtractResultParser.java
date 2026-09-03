package com.ruoyi.system.kb.graph.extract;

import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.ruoyi.common.utils.StringUtils;
import com.ruoyi.system.kb.graph.GraphEntity;
import com.ruoyi.system.kb.graph.GraphRelation;
import com.ruoyi.system.kb.graph.KbGraphConstants;
import com.ruoyi.system.kb.graph.provenance.GraphExtractOutcome;

/**
 * 宽松解析抽取 JSON:剥 markdown 围栏、去尾随逗号、容忍截断。
 * <p>解析失败标记 {@link GraphExtractOutcome#PARSE_FAILED}，与合法空结果区分。
 */
public final class ExtractResultParser
{
    private static final Logger log = LoggerFactory.getLogger(ExtractResultParser.class);

    private ExtractResultParser() {}

    public static ExtractResult parse(String raw)
    {
        ExtractResult empty = new ExtractResult();
        if (StringUtils.isEmpty(raw))
        {
            // 空串由调用方解释为 LLM_FAILED 或 VALID_EMPTY
            return empty;
        }
        String text = stripFence(raw.trim());
        text = fixTrailingCommas(text);
        text = tryCloseTruncated(text);
        try
        {
            JSONObject obj = JSON.parseObject(text);
            if (obj == null)
            {
                empty.setOutcome(GraphExtractOutcome.PARSE_FAILED);
                empty.setErrorDetail("JSON 对象为空");
                return empty;
            }
            // 必须像抽取协议（至少有 entities 或 relationships 键之一）
            if (!obj.containsKey("entities") && !obj.containsKey("relationships"))
            {
                empty.setOutcome(GraphExtractOutcome.PARSE_FAILED);
                empty.setErrorDetail("缺少 entities/relationships 字段");
                return empty;
            }
            ExtractResult result = new ExtractResult();
            result.setEntities(parseEntities(obj.getJSONArray("entities")));
            result.setRelations(parseRelations(obj.getJSONArray("relationships")));
            // 解析成功但不设最终 outcome，交给 Validator
            return result;
        }
        catch (Exception e)
        {
            log.warn("抽取 JSON 解析失败: {} — raw 前 200 字: {}",
                e.getMessage(), raw.length() > 200 ? raw.substring(0, 200) : raw);
            empty.setOutcome(GraphExtractOutcome.PARSE_FAILED);
            empty.setErrorDetail(e.getMessage());
            return empty;
        }
    }

    static String stripFence(String text)
    {
        String t = text.trim();
        if (t.startsWith("```"))
        {
            int firstNl = t.indexOf('\n');
            if (firstNl > 0)
            {
                t = t.substring(firstNl + 1);
            }
            else
            {
                t = t.replaceFirst("^```\\w*", "");
            }
            int end = t.lastIndexOf("```");
            if (end >= 0)
            {
                t = t.substring(0, end);
            }
        }
        // 有时模型在 JSON 前后加说明
        int brace = t.indexOf('{');
        int lastBrace = t.lastIndexOf('}');
        if (brace >= 0 && lastBrace > brace)
        {
            t = t.substring(brace, lastBrace + 1);
        }
        return t.trim();
    }

    static String fixTrailingCommas(String text)
    {
        // ,] 或 ,} → ] 或 }
        return text.replaceAll(",\\s*([\\]}])", "$1");
    }

    static String tryCloseTruncated(String text)
    {
        // 用栈记录未闭合括号,按反序补全(避免 ]} 顺序错乱)
        java.util.ArrayDeque<Character> stack = new java.util.ArrayDeque<>();
        boolean inStr = false;
        boolean escape = false;
        for (int i = 0; i < text.length(); i++)
        {
            char c = text.charAt(i);
            if (escape)
            {
                escape = false;
                continue;
            }
            if (c == '\\' && inStr)
            {
                escape = true;
                continue;
            }
            if (c == '"')
            {
                inStr = !inStr;
                continue;
            }
            if (inStr)
            {
                continue;
            }
            if (c == '{' || c == '[')
            {
                stack.push(c);
            }
            else if (c == '}' || c == ']')
            {
                if (!stack.isEmpty())
                {
                    char open = stack.peek();
                    if ((c == '}' && open == '{') || (c == ']' && open == '['))
                    {
                        stack.pop();
                    }
                }
            }
        }
        StringBuilder sb = new StringBuilder(text);
        if (inStr)
        {
            sb.append('"');
        }
        while (!stack.isEmpty())
        {
            char open = stack.pop();
            sb.append(open == '{' ? '}' : ']');
        }
        return sb.toString();
    }

    private static List<GraphEntity> parseEntities(JSONArray arr)
    {
        List<GraphEntity> list = new ArrayList<>();
        if (arr == null)
        {
            return list;
        }
        for (int i = 0; i < arr.size() && list.size() < KbGraphConstants.MAX_ENTITIES_PER_CHUNK; i++)
        {
            JSONObject o = arr.getJSONObject(i);
            if (o == null)
            {
                continue;
            }
            String name = o.getString("name");
            if (StringUtils.isEmpty(name))
            {
                continue;
            }
            GraphEntity e = new GraphEntity();
            e.setName(name.trim());
            e.setType(o.getString("type"));
            e.setDescription(o.getString("description"));
            list.add(e);
        }
        return list;
    }

    private static List<GraphRelation> parseRelations(JSONArray arr)
    {
        List<GraphRelation> list = new ArrayList<>();
        if (arr == null)
        {
            return list;
        }
        for (int i = 0; i < arr.size() && list.size() < KbGraphConstants.MAX_RELATIONS_PER_CHUNK; i++)
        {
            JSONObject o = arr.getJSONObject(i);
            if (o == null)
            {
                continue;
            }
            String source = o.getString("source");
            String target = o.getString("target");
            if (StringUtils.isEmpty(source) || StringUtils.isEmpty(target))
            {
                continue;
            }
            GraphRelation r = new GraphRelation();
            r.setSourceName(source.trim());
            r.setTargetName(target.trim());
            r.setKeywords(o.getString("keywords"));
            r.setDescription(o.getString("description"));
            list.add(r);
        }
        return list;
    }

    public static class ExtractResult
    {
        private List<GraphEntity> entities = new ArrayList<>();
        private List<GraphRelation> relations = new ArrayList<>();
        private String outcome;
        private String errorDetail;
        private int retryCount;
        private int repairedEndpoints;
        private long elapsedMs;
        private boolean fromCache;

        public List<GraphEntity> getEntities()
        {
            return entities;
        }

        public void setEntities(List<GraphEntity> entities)
        {
            this.entities = entities != null ? new ArrayList<>(entities) : new ArrayList<>();
        }

        public List<GraphRelation> getRelations()
        {
            return relations;
        }

        public void setRelations(List<GraphRelation> relations)
        {
            this.relations = relations != null ? new ArrayList<>(relations) : new ArrayList<>();
        }

        public String getOutcome()
        {
            return outcome;
        }

        public void setOutcome(String outcome)
        {
            this.outcome = outcome;
        }

        public String getErrorDetail()
        {
            return errorDetail;
        }

        public void setErrorDetail(String errorDetail)
        {
            this.errorDetail = errorDetail;
        }

        public int getRetryCount()
        {
            return retryCount;
        }

        public void setRetryCount(int retryCount)
        {
            this.retryCount = retryCount;
        }

        public int getRepairedEndpoints()
        {
            return repairedEndpoints;
        }

        public void setRepairedEndpoints(int repairedEndpoints)
        {
            this.repairedEndpoints = repairedEndpoints;
        }

        public long getElapsedMs()
        {
            return elapsedMs;
        }

        public void setElapsedMs(long elapsedMs)
        {
            this.elapsedMs = elapsedMs;
        }

        public boolean isFromCache()
        {
            return fromCache;
        }

        public void setFromCache(boolean fromCache)
        {
            this.fromCache = fromCache;
        }
    }
}
