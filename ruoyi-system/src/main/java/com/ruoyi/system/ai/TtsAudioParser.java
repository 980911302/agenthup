package com.ruoyi.system.ai;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;

/**
 * 解析 OpenAI / 百炼 SpeechSynthesizer 风格的 TTS JSON。
 */
public final class TtsAudioParser
{
    private TtsAudioParser()
    {
    }

    public static String audioUrl(String json)
    {
        JSONObject o = parse(json);
        if (o == null)
        {
            return null;
        }
        String url = firstText(o, "url", "audio_url");
        if (url != null)
        {
            return url;
        }
        JSONObject output = o.getJSONObject("output");
        if (output != null)
        {
            url = firstText(output, "url", "audio_url");
            if (url != null)
            {
                return url;
            }
            JSONObject audio = output.getJSONObject("audio");
            if (audio != null)
            {
                url = firstText(audio, "url", "audio_url");
                if (url != null)
                {
                    return url;
                }
            }
        }
        JSONArray data = o.getJSONArray("data");
        if (data != null && !data.isEmpty())
        {
            Object first = data.get(0);
            if (first instanceof JSONObject item)
            {
                return firstText(item, "url", "audio_url");
            }
        }
        return null;
    }

    public static String errorMessage(String json)
    {
        JSONObject o = parse(json);
        if (o == null)
        {
            return "语音合成失败";
        }
        String msg = firstText(o, "message", "msg");
        if (msg != null)
        {
            return msg;
        }
        JSONObject err = o.getJSONObject("error");
        if (err != null)
        {
            msg = firstText(err, "message", "msg", "code");
            if (msg != null)
            {
                return msg;
            }
        }
        return "语音合成失败";
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
