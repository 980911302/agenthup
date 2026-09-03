package com.ruoyi.system.ai.run;

/**
 * Run 事件流的订阅者在场判定。
 *
 * <p>渠道工具的执行体在客户端(浏览器插件侧边栏)里,没有客户端在线时继续挂着只是
 * 白白占满超时。实现落在接入层的 {@code ChatSubscriptionRegistry},这里只留一个查询口,
 * 免得 ruoyi-system 反向依赖 ruoyi-admin。</p>
 *
 * <p>判定范围是<b>本实例</b>。这不是缺陷而是与现状对齐:渠道工具的挂起表与回传
 * ({@code ChannelToolBroker#complete}) 本来就是进程内的,别的实例上的连接即使在线
 * 也答不上这一轮,判成不在场反而更准。</p>
 */
public interface RunSubscriberPresence
{
    /** 本实例上是否还有连接订阅着这一轮的事件流。 */
    boolean hasSubscriber(String runId);
}
