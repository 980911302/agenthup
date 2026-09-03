package com.ruoyi.system.ai.userfile;

import java.util.List;

import org.springframework.web.multipart.MultipartFile;

import com.ruoyi.ai.contract.storage.ObjectReadHandle;
import com.ruoyi.system.domain.AiUserFile;

/**
 * 用户个人文件服务。
 *
 * <p>每个方法都以 {@code userId} 开头,归属校验一律下沉到 SQL 的 where 条件里,
 * 不依赖调用方记得过滤。
 *
 * @author ruoyi
 */
public interface IAiUserFileService
{
    /** 对象存储是否可用。未配置时前端应给出引导而不是报错。 */
    boolean storageAvailable();

    /**
     * 列出用户文件。
     *
     * @param keyword 文件名模糊关键字,可空
     * @param orderBy date(默认) / name / size
     */
    List<AiUserFile> list(Long userId, String keyword, String orderBy);

    /** 配额快照。 */
    UserFileQuota quota(Long userId);

    /** 单条详情,不属于该用户则返回 null。 */
    AiUserFile get(Long userId, Long fileId);

    /**
     * 上传。同一用户传入相同内容时走秒传:复用已有对象,只新增一条元数据记录。
     *
     * @param createBy 创建者登录名
     */
    AiUserFile upload(Long userId, String createBy, MultipartFile file);

    /**
     * 把会话工作区里的一个文件存进个人文件空间。
     *
     * <p>与 {@link #attachToSession} 方向相反:那边是「个人文件 → 工作区副本」,这边是把
     * AI 在工作区产出的东西收进个人空间长期保存 —— 工作区会随会话删除而清空,存进来才留得住。
     *
     * @param path      相对工作区根的路径,如 {@code report.md}
     * @param projectId 项目会话传项目ID,普通会话传 null
     */
    AiUserFile saveFromWorkspace(Long userId, String createBy, String sessionId, String path, Long projectId);

    /**
     * 打开下载流。调用方负责 close。
     *
     * @return 读取句柄;文件不存在或不属于该用户时抛业务异常
     */
    ObjectReadHandle open(Long userId, Long fileId);

    /**
     * 生成带时效的直链。
     *
     * @param asAttachment true 走另存为,false 内联预览
     */
    String presignedUrl(Long userId, Long fileId, boolean asAttachment);

    /** 重命名。只改展示名,object_key 不动。 */
    AiUserFile rename(Long userId, Long fileId, String newName);

    /** 软删元数据;该对象不再被任何存活记录引用时,顺带删除存储上的本体。 */
    void delete(Long userId, Long fileId);

    /**
     * 把个人文件投递进某个会话的工作区 {@code uploads/},让模型能用 read 工具读到。
     *
     * <p>这是个人文件区与 AI 的唯一衔接点。刻意做成<b>拷贝</b>而非引用:会话工作区必须是
     * POSIX 目录,{@code bash} / {@code grep} 直接在上面跑进程,对象存储给不了这个语义。
     *
     * @param projectId 项目会话传项目ID,普通会话传 null
     * @return 相对沙箱根的路径,如 {@code uploads/report.csv},可直接作为 read 工具入参
     */
    String attachToSession(Long userId, Long fileId, String sessionId, Long projectId);
}
