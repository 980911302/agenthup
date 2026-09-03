package com.ruoyi.system.ai.event;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.ruoyi.system.ai.session.SessionAccessGuard;
import com.ruoyi.system.domain.AiChatSpecialEvent;
import com.ruoyi.system.domain.AiChatSpecialEventSummary;
import com.ruoyi.system.mapper.AiChatSpecialEventMapper;
import com.ruoyi.system.tool.UiArtifactNames;
import org.springframework.stereotype.Service;

/**
 * 特殊事件查询:摘要跟时间线走,正文按需拉。鉴权走 {@link SessionAccessGuard}。
 */
@Service
public class SpecialEventService
{
    private final AiChatSpecialEventMapper mapper;
    private final SessionAccessGuard sessionGuard;

    public SpecialEventService(AiChatSpecialEventMapper mapper, SessionAccessGuard sessionGuard)
    {
        this.mapper = mapper;
        this.sessionGuard = sessionGuard;
    }

    public Map<String, List<Map<String, Object>>> summariesByMessage(String sessionId)
    {
        if (sessionId == null || sessionId.isBlank() || mapper == null)
        {
            return Map.of();
        }
        List<AiChatSpecialEventSummary> rows = mapper.selectSummariesBySessionId(sessionId);
        if (rows == null || rows.isEmpty())
        {
            return Map.of();
        }
        Map<String, List<Map<String, Object>>> out = new LinkedHashMap<>();
        for (AiChatSpecialEventSummary row : rows)
        {
            if (row == null || row.getMessageId() == null || row.getName() == null)
            {
                continue;
            }
            JSONObject payload = parseObject(row.getPayload());
            fillCounts(row);
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("name", row.getName());
            item.put("fileCount", row.getFileCount());
            item.put("chunkCount", row.getChunkCount());
            if (UiArtifactNames.WORKSPACE_CHANGES.equals(row.getName()) && payload != null)
            {
                item.put("createdCount", payload.getIntValue("createdCount"));
                item.put("modifiedCount", payload.getIntValue("modifiedCount"));
                item.put("deletedCount", payload.getIntValue("deletedCount"));
                item.put("truncated", payload.getBooleanValue("truncated"));
                JSONArray files = payload.getJSONArray("files");
                item.put("files", files == null ? List.of() : new ArrayList<>(files));
            }
            out.computeIfAbsent(String.valueOf(row.getMessageId()), k -> new ArrayList<>()).add(item);
        }
        return out;
    }

    public List<AiChatSpecialEvent> payloadsAuthorized(String sessionId, Long messageId, String name,
                                                       Long userId, boolean admin)
    {
        sessionGuard.requireOwned(sessionId, userId, admin);
        if (messageId == null || name == null || name.isBlank() || mapper == null)
        {
            return Collections.emptyList();
        }
        List<AiChatSpecialEvent> rows = mapper.selectPayloads(sessionId, messageId, name);
        return rows != null ? rows : Collections.emptyList();
    }

    static void fillCounts(AiChatSpecialEventSummary row)
    {
        if (row == null)
        {
            return;
        }
        if ((row.getFileCount() > 0 || row.getChunkCount() > 0) && row.getPayload() == null)
        {
            return;
        }
        JSONObject payload = parseObject(row.getPayload());
        if (payload == null)
        {
            return;
        }
        JSONArray files = payload.getJSONArray("files");
        int fileCount = payload.getIntValue("fileCount");
        if (fileCount <= 0 && files != null)
        {
            fileCount = files.size();
        }
        int chunkCount = payload.getIntValue("chunkCount");
        row.setFileCount(fileCount);
        row.setChunkCount(chunkCount);
        row.setPayload(null);
    }

    private static JSONObject parseObject(String raw)
    {
        if (raw == null || raw.isBlank())
        {
            return null;
        }
        try
        {
            return JSON.parseObject(raw);
        }
        catch (Exception ex)
        {
            return null;
        }
    }
}
