package com.ruoyi.system.kb.graph.community;

/**
 * 层级社区检测。GDS 不可用时实现必须明确降级，不得伪装成功。
 */
public interface KbCommunityDetector
{
    GdsCapability probe();

    /**
     * 对指定知识库运行社区发现。
     * @param graphVersion 与当前图内容绑定的版本指纹
     */
    CommunityDetectionResult detect(Long kbId, String graphVersion);
}
