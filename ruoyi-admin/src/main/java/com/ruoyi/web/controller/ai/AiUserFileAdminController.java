package com.ruoyi.web.controller.ai;

import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.ruoyi.common.annotation.Log;
import com.ruoyi.common.core.controller.BaseController;
import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.common.core.page.TableDataInfo;
import com.ruoyi.common.enums.BusinessType;
import com.ruoyi.system.ai.userfile.IAiUserFileAdminService;
import com.ruoyi.system.domain.AiUserFile;

import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * 个人文件管理端(后台 ruoyi-ui)。
 *
 * <p>与 C 端的 {@link AiUserFileController}(`/ai/files`)是两套东西,不要合并:
 * <ul>
 *   <li>那边无 {@code @PreAuthorize},每个方法 {@code getUserId()} 只碰自己的文件,
 *       归属焊死在 SQL 的 where 里;</li>
 *   <li>这边是后台权限点 {@code ai:userfile:*},跨用户查看与强制删除。</li>
 * </ul>
 * 把两者塞进一个 Controller 意味着「能不能看到别人的文件」要靠运行时判断,
 * 分开则由路由和权限注解在入口就切干净。
 *
 * @author ruoyi
 */
@Tag(name = "个人文件管理", description = "管理员查看与清理用户个人文件")
@RestController
@RequestMapping("/ai/userfile")
public class AiUserFileAdminController extends BaseController
{
    @Autowired
    private IAiUserFileAdminService adminService;

    /** 跨用户文件列表。支持按 userId / fileName / contentType / 用户名筛选。 */
    @PreAuthorize("@ss.hasPermi('ai:userfile:list')")
    @GetMapping("/list")
    public TableDataInfo list(AiUserFile query)
    {
        startPage();
        List<AiUserFile> list = adminService.selectAiUserFileList(query);
        return getDataTable(list);
    }

    /** 全局汇总 + 当前生效的配额配置。 */
    @PreAuthorize("@ss.hasPermi('ai:userfile:list')")
    @GetMapping("/totals")
    public AjaxResult totals()
    {
        return AjaxResult.success(adminService.totals());
    }

    /** 按用户汇总占用,倒序。 */
    @PreAuthorize("@ss.hasPermi('ai:userfile:list')")
    @GetMapping("/usage")
    public AjaxResult usage()
    {
        List<Map<String, Object>> list = adminService.usageByUser();
        return AjaxResult.success(list);
    }

    /** 强制删除:软删台账,并清理不再被引用的存储对象。 */
    @PreAuthorize("@ss.hasPermi('ai:userfile:remove')")
    @Log(title = "个人文件管理", businessType = BusinessType.DELETE)
    @DeleteMapping("/{fileIds}")
    public AjaxResult remove(@PathVariable Long[] fileIds)
    {
        return toAjax(adminService.deleteAiUserFileByIds(fileIds));
    }
}
