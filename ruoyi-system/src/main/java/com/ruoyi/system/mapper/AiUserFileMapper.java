package com.ruoyi.system.mapper;

import java.util.List;
import java.util.Map;

import org.apache.ibatis.annotations.Param;

import com.ruoyi.system.domain.AiUserFile;

/**
 * 用户个人文件 Mapper。
 *
 * <p>所有查询方法都强制带 {@code userId} 入参 —— 越权读别人文件这类问题,
 * 靠 Service 层记得传条件是守不住的,把它做进 SQL 签名里才是。
 *
 * @author ruoyi
 */
public interface AiUserFileMapper
{
    /** 按用户列出文件,支持文件名关键字与排序字段。 */
    List<AiUserFile> selectByUser(AiUserFile query);

    /** 按主键取单条,同时校验归属。 */
    AiUserFile selectByIdAndUser(@Param("fileId") Long fileId, @Param("userId") Long userId);

    /** 按内容哈希查同一用户已有的文件,用于秒传。 */
    AiUserFile selectByUserAndHash(@Param("userId") Long userId, @Param("contentHash") String contentHash);

    /** 该用户已用字节数。 */
    Long sumSizeByUser(@Param("userId") Long userId);

    /** 该用户文件条数。 */
    int countByUser(@Param("userId") Long userId);

    /** 同一 object_key 还有几条存活引用,决定对象本体能不能真删。 */
    int countLiveByObjectKey(@Param("objectKey") String objectKey);

    /* ---------------- 以下为管理端查询:刻意不带 userId 强制约束 ----------------
     * C 端方法把归属焊在 SQL 的 where 里,管理端反过来需要跨用户看。两组分开命名
     * (admin 前缀)是为了让「这个查询能看到别人的数据」在调用点一眼可见。 */

    /** 管理端列表:按用户/文件名筛选,配合 PageHelper 分页。 */
    List<AiUserFile> selectAdminList(AiUserFile query);

    /** 管理端按 id 批量取,用于删除前拿到 object_key。 */
    List<AiUserFile> selectAdminByIds(@Param("fileIds") Long[] fileIds);

    /** 管理端批量软删。 */
    int softDeleteByIds(@Param("fileIds") Long[] fileIds);

    /** 按用户汇总占用:用户ID、文件数、总字节。 */
    List<Map<String, Object>> selectAdminUsageByUser();

    /** 全局汇总:总文件数与总字节。 */
    Map<String, Object> selectAdminTotals();

    int insertAiUserFile(AiUserFile file);

    int updateAiUserFile(AiUserFile file);

    /** 软删,同时校验归属:where 带 user_id,越权删除直接影响 0 行。 */
    int softDeleteByIdAndUser(@Param("fileId") Long fileId, @Param("userId") Long userId);
}
