package com.ruoyi.system.mapper;

import java.util.List;
import org.apache.ibatis.annotations.Param;
import com.ruoyi.system.domain.KbDocument;

/**
 * 知识库文档表 数据层
 *
 * @author ruoyi
 */
public interface KbDocumentMapper
{
    public KbDocument selectKbDocumentById(Long docId);

    public List<KbDocument> selectKbDocumentList(KbDocument kbDocument);

    /** 按ID批量查询(软删保护:仅未删除记录),供删除时回查文件路径 */
    public List<KbDocument> selectKbDocumentByIds(Long[] docIds);

    /** 按内容 hash 判重(同一知识库内) */
    public KbDocument selectByKbIdAndContentHash(@Param("kbId") Long kbId, @Param("contentHash") String contentHash);

    public int insertKbDocument(KbDocument kbDocument);

    public int updateKbDocument(KbDocument kbDocument);

    /** 处理进度更新(允许清空 error 字段) */
    public int updateParseProgress(KbDocument kbDocument);

    /** 软删单条 */
    public int deleteKbDocumentById(Long docId);

    /** 软删批量 */
    public int deleteKbDocumentByIds(Long[] docIds);

    /** 物理删除单条(删除文件后清理记录) */
    public int purgeKbDocumentById(Long docId);
}