package com.ruoyi.system.ai;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;

/**
 * 解析 OpenAI / grok2api 风格的视频任务 JSON。
 */
public final class VideoJobParser
{
    public enum Status
    {
        PENDING, COMPLETED, FAILED
    }

    private VideoJobParser()
    {
    }

    public static String jobId(String json)
    {
        JSONObject o = parse(json);
        if (o == null)
        {
            return null;
        }
        String id = firstText(o, "request_id", "id", "job_id");
        if (id != null)
        {
            return id;
        }
        JSONObject data = o.getJSONObject("data");
        return data == null ? null : firstText(data, "request_id", "id");
    }

    public static Status status(String json)
    {
        JSONObject o = parse(json);
        if (o == null)
        {
            return Status.PENDING;
        }
        String raw = firstText(o, "status", "state");
        if (raw == null)
        {
            return Status.PENDING;
        }
        String s = raw.trim().toLowerCase();
        if ("done".equals(s) || "completed".equals(s) || "succeeded".equals(s) || "success".equals(s))
        {
            return Status.COMPLETED;
        }
        if ("failed".equals(s) || "error".equals(s) || "cancelled".equals(s) || "canceled".equals(s))
        {
            return Status.FAILED;
        }
        return Status.PENDING;
    }

    public static String videoUrl(String json)
    {
        JSONObject o = parse(json);
        if (o == null)
        {
            return null;
        }
        String url = firstText(o, "url", "video_url");
        if (url != null)
        {
            return url;
        }
        JSONObject video = o.getJSONObject("video");
        if (video != null)
        {
            url = firstText(video, "url", "video_url");
            if (url != null)
            {
                return url;
            }
        }
        JSONObject output = o.getJSONObject("output");
        return output == null ? null : firstText(output, "url", "video_url");
    }

    public static String errorMessage(String json)
    {
        JSONObject o = parse(json);
        if (o == null)
        {
            return "视频任务失败";
        }
        String msg = firstText(o, "message", "error");
        if (msg != null)
        {
            return msg;
        }
        JSONObject err = o.getJSONObject("error");
        if (err != null)
        {
            msg = firstText(err, "message", "code");
            if (msg != null)
            {
                return msg;
            }
        }
        return "视频任务失败";
    }

    private static JSONObject parse(String json)
    {
        if (json == null || json.isBlank())
        {
            return null;
        }
        try
        {
            return JSON.parseObject(json);
        }
        catch (Exception e)
        {
            return null;
        }
    }

    private static String firstText(JSONObject o, String... keys)
    {
        for (String key : keys)
        {
            Object v = o.get(key);
            if (v instanceof String s && !s.isBlank())
            {
                return s.trim();
            }
        }
        return null;
    }
}
