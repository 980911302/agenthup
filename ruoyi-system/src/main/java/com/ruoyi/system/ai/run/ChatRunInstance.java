package com.ruoyi.system.ai.run;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.UUID;

/** 当前应用实例的运行标识，用于 Redis 广播去重和执行节点归属。 */
@Component
public class ChatRunInstance
{
    private final String id;

    public ChatRunInstance(@Value("${spring.application.name:ruoyi}") String applicationName)
    {
        String host = System.getenv().getOrDefault("HOSTNAME", "local");
        String prefix = applicationName + "@" + host;
        if (prefix.length() > 88)
        {
            prefix = prefix.substring(0, 88);
        }
        this.id = prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
    }

    public String id()
    {
        return id;
    }
}
