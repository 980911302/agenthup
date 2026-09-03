package com.ruoyi.system.kb.graph.community;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.stereotype.Component;

/**
 * GDS/图不可用时的降级：明确 skip，不写假社区。
 */
@Component
@ConditionalOnMissingBean(name = "neo4jGdsCommunityDetector")
public class NoOpCommunityDetector implements KbCommunityDetector
{
    @Override
    public GdsCapability probe()
    {
        return GdsCapability.unavailable("GDS/Neo4j 社区检测未启用");
    }

    @Override
    public CommunityDetectionResult detect(Long kbId, String graphVersion)
    {
        CommunityDetectionResult r = new CommunityDetectionResult();
        r.setAvailable(false);
        r.setSkipReason(probe().getReason());
        r.setGraphVersion(graphVersion);
        r.setAlgorithm("noop");
        return r;
    }
}
