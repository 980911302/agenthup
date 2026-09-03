package com.ruoyi.system.mapper;

import java.util.List;
import com.ruoyi.system.domain.KbGraphRun;

/**
 * 图抽取运行 kb_graph_run
 */
public interface KbGraphRunMapper
{
    KbGraphRun selectByRunId(Long runId);

    List<KbGraphRun> selectByDocId(Long docId);

    KbGraphRun selectLatestByDocId(Long docId);

    int insert(KbGraphRun row);

    int update(KbGraphRun row);

    int deleteByDocId(Long docId);

    int deleteByKbId(Long kbId);
}
