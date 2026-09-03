package com.ruoyi.system.mapper;

import java.util.List;
import org.apache.ibatis.annotations.Param;
import com.ruoyi.system.domain.KbGraphCommunity;
import com.ruoyi.system.domain.KbGraphCommunityReport;
import com.ruoyi.system.domain.KbGraphCommunityReportSource;
import com.ruoyi.system.domain.KbGraphEntityCommunity;
import com.ruoyi.system.domain.KbGraphIndex;

public interface KbGraphCommunityMapper
{
    KbGraphIndex selectIndex(Long kbId);

    int upsertIndex(KbGraphIndex row);

    int markDirty(@Param("kbId") Long kbId, @Param("graphVersion") String graphVersion);

    int deleteCommunities(@Param("kbId") Long kbId, @Param("graphVersion") String graphVersion);

    int deleteEntityCommunities(@Param("kbId") Long kbId, @Param("graphVersion") String graphVersion);

    int deleteAllCommunitiesByKb(Long kbId);

    int deleteAllEntityCommunitiesByKb(Long kbId);

    int batchInsertCommunities(@Param("list") List<KbGraphCommunity> list);

    int batchInsertEntityCommunities(@Param("list") List<KbGraphEntityCommunity> list);

    List<KbGraphCommunity> selectCommunities(@Param("kbId") Long kbId,
        @Param("graphVersion") String graphVersion);

    List<KbGraphEntityCommunity> selectEntityCommunities(@Param("kbId") Long kbId,
        @Param("graphVersion") String graphVersion);

    int countEntityCommunitiesAtLevel(@Param("kbId") Long kbId,
        @Param("graphVersion") String graphVersion, @Param("level") int level);

    // --- reports (KB-GR-09) ---

    int insertReport(KbGraphCommunityReport row);

    int batchInsertReportSources(@Param("list") List<KbGraphCommunityReportSource> list);

    int deleteReportsByKb(Long kbId);

    int deleteReportSourcesByKb(Long kbId);

    List<KbGraphCommunityReport> selectReports(@Param("kbId") Long kbId,
        @Param("graphVersion") String graphVersion);

    List<KbGraphCommunityReportSource> selectReportSources(@Param("reportId") Long reportId);

    KbGraphCommunityReport selectReportByHash(@Param("kbId") Long kbId,
        @Param("contentHash") String contentHash);
}
