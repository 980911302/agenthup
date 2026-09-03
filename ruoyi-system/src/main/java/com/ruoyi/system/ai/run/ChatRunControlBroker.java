package com.ruoyi.system.ai.run;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/** 跨实例运行控制命令；支持 cancel 与 pause。 */
@Component
public class ChatRunControlBroker
{
    public static final String CHANNEL = "ai:chat:control";
    public static final String ACTION_CANCEL = "cancel";
    public static final String ACTION_PAUSE = "pause";
    private static final Logger log = LoggerFactory.getLogger(ChatRunControlBroker.class);

    private final StringRedisTemplate redisTemplate;
    private final ChatRunExecutor executor;
    private final ChatRunInstance instance;

    public ChatRunControlBroker(StringRedisTemplate redisTemplate,
                                ChatRunExecutor executor,
                                ChatRunInstance instance)
    {
        this.redisTemplate = redisTemplate;
        this.executor = executor;
        this.instance = instance;
    }

    public void cancel(String runId)
    {
        executor.cancelLocal(runId);
        broadcast(ACTION_CANCEL, runId);
    }

    /**
     * 请求优雅暂停:只立 {@code shouldContinue()} 闸门,不 dispose 当前工具。
     * 循环退出后 run 落 {@code CANCELLED} + {@code PAUSED_BY_USER},不是 {@code CANCELLED_BY_USER}。
     */
    public void pause(String runId)
    {
        executor.pauseLocal(runId);
        broadcast(ACTION_PAUSE, runId);
    }

    public void acceptRemote(String payload)
    {
        try
        {
            JSONObject command = JSON.parseObject(payload);
            if (command == null || instance.id().equals(command.getString("originInstanceId")))
            {
                return;
            }
            String action = command.getString("action");
            String runId = command.getString("runId");
            if (ACTION_CANCEL.equals(action))
            {
                executor.cancelLocal(runId);
            }
            else if (ACTION_PAUSE.equals(action))
            {
                executor.pauseLocal(runId);
            }
        }
        catch (RuntimeException e)
        {
            log.warn("忽略无法解析的运行控制指令", e);
        }
    }

    private void broadcast(String action, String runId)
    {
        JSONObject command = new JSONObject();
        command.put("action", action);
        command.put("runId", runId);
        command.put("originInstanceId", instance.id());
        try
        {
            redisTemplate.convertAndSend(CHANNEL, command.toJSONString());
        }
        catch (RuntimeException e)
        {
            log.warn("广播运行控制指令失败，本实例处理已执行: action={} runId={}", action, runId, e);
        }
    }
}

