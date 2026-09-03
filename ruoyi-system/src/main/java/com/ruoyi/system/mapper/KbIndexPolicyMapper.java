package com.ruoyi.system.mapper;

import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Param;
import com.ruoyi.system.domain.KbIndexJob;
import com.ruoyi.system.domain.KbIndexPolicyVersion;

public interface KbIndexPolicyMapper
{
    Map<String, Object> selectPolicyPointer();

    int upsertPolicyPointer(@Param("draftPayloadJson") String draftPayloadJson,
                            @Param("publishedVersionId") Long publishedVersionId,
                            @Param("maxConcurrentJobs") Integer maxConcurrentJobs,
                            @Param("updateBy") String updateBy);

    Integer selectMaxVersionNo();

    int insertVersion(KbIndexPolicyVersion v);

    KbIndexPolicyVersion selectVersionById(Long versionId);

    List<KbIndexPolicyVersion> selectVersions(@Param("limit") int limit);

    KbIndexPolicyVersion selectPublishedVersion();

    int countRunningJobs();

    int insertJob(KbIndexJob job);

    int updateJob(KbIndexJob job);

    KbIndexJob selectJobById(Long jobId);

    List<KbIndexJob> selectJobsByKb(@Param("kbId") Long kbId, @Param("limit") int limit);

    List<KbIndexJob> selectRecentJobs(@Param("limit") int limit);

    int updateKbPolicyBinding(@Param("kbId") Long kbId,
                              @Param("activeVersionId") Long activeVersionId,
                              @Param("desiredVersionId") Long desiredVersionId,
                              @Param("previousVersionId") Long previousVersionId,
                              @Param("indexState") String indexState);

    /** 绑定策略版本时同步技术快照字段 */
    int applyPolicySnapshotToKb(@Param("kbId") Long kbId,
                                @Param("embeddingModelCode") String embeddingModelCode,
                                @Param("extractModelCode") String extractModelCode,
                                @Param("chunkStrategy") String chunkStrategy,
                                @Param("chunkSize") Integer chunkSize,
                                @Param("chunkOverlap") Integer chunkOverlap,
                                @Param("graphEnabled") String graphEnabled);

    List<Map<String, Object>> selectUpgradeCandidates(@Param("publishedVersionId") Long publishedVersionId);
}
