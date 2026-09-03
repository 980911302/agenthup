package com.ruoyi.system.task;

import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import com.ruoyi.system.domain.AiChannel;
import com.ruoyi.system.service.IAiChannelService;

/**
 * AI 模块定时任务
 *
 * ryTask bean 提供方法给 quartz 调用,在 sys_job 表里配置
 * invoke_target 格式: aiTask.checkChannels()
 *
 * @author ruoyi
 */
@Component("aiTask")
public class AiTask
{
    private static final Logger log = LoggerFactory.getLogger(AiTask.class);

    @Autowired
    private IAiChannelService aiChannelService;

    /**
     * 批量探活所有启用的渠道
     */
    public void checkChannels()
    {
        AiChannel query = new AiChannel();
        query.setStatus("0");
        List<AiChannel> channels = aiChannelService.selectAiChannelList(query);
        int ok = 0, fail = 0;
        for (AiChannel c : channels)
        {
            int r = aiChannelService.checkHealth(c.getChannelId());
            if (r == 1)
            {
                ok++;
            }
            else if (r == 2)
            {
                fail++;
            }
        }
        log.info("AI 渠道健康检查完成: 正常={}, 异常={}, 总数={}", ok, fail, channels.size());
    }
}
