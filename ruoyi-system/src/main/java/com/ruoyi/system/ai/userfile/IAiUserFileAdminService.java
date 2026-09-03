package com.ruoyi.system.ai.userfile;

import java.util.List;
import java.util.Map;

import com.ruoyi.system.domain.AiUserFile;

/**
 * 个人文件的管理端服务。
 *
 * <p>与 {@link IAiUserFileService} 的区别是<b>视角</b>:那边每个方法都以 userId 开头、
 * 归属焊死在 SQL 的 where 里,这边跨用户看。两个接口分开而不是加个 boolean 开关,
 * 是为了让「能看到别人数据」这件事在类型上就分得清,不依赖调用方传对参数。
 *
 * @author ruoyi
 */
public interface IAiUserFileAdminService
{
    /** 跨用户列表,配合 PageHelper 分页。 */
    List<AiUserFile> selectAiUserFileList(AiUserFile query);

    /**
     * 管理员批量删除。软删台账,并清理不再被引用的存储对象。
     *
     * @return 实际软删的行数
     */
    int deleteAiUserFileByIds(Long[] fileIds);

    /** 按用户汇总占用,给管理端的空间统计用。 */
    List<Map<String, Object>> usageByUser();

    /** 全局汇总:文件数、总字节、涉及用户数,外加当前生效的单用户配额。 */
    Map<String, Object> totals();
}
