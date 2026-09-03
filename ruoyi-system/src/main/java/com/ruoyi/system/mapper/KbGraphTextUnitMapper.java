package com.ruoyi.system.mapper;

import java.util.List;
import org.apache.ibatis.annotations.Param;
import com.ruoyi.system.domain.KbGraphTextUnit;

/**
 * 图抽取 TextUnit
 */
public interface KbGraphTextUnitMapper
{
    int insert(KbGraphTextUnit row);

    int batchInsert(@Param("list") List<KbGraphTextUnit> list);

    int insertChunkMapping(@Param("textUnitId") Long textUnitId, @Param("chunkId") Long chunkId);

    int batchInsertChunkMappings(@Param("textUnitId") Long textUnitId,
        @Param("chunkIds") List<Long> chunkIds);

    List<KbGraphTextUnit> selectByDocId(Long docId);

    List<Long> selectChunkIdsByTextUnitId(Long textUnitId);

    int deleteMappingsByDocId(Long docId);

    int deleteMappingsByKbId(Long kbId);

    int deleteByDocId(Long docId);

    int deleteByKbId(Long kbId);
}
