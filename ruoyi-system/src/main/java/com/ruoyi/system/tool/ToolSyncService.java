package com.ruoyi.system.tool;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.mcp.SyncMcpToolCallback;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import com.ruoyi.system.domain.AiMcpServer;
import com.ruoyi.system.domain.AiTool;
import com.ruoyi.system.mapper.AiAgentMapper;
import com.ruoyi.system.mapper.AiMcpServerMapper;
import com.ruoyi.system.mapper.AiToolMapper;

/**
 * 工具同步服务
 *
 * 统一从 Spring 容器中的 ToolCallbackProvider 收集工具(内置 + MCP 全部走 Spring AI 框架):
 *  - 内置工具:MethodToolCallbackProvider(MethodToolCallback,@Tool 注解)
 *  - MCP 工具:SyncMcpToolCallbackProvider(SyncMcpToolCallback,spring-ai-mcp 动态注册)
 *
 * 识别规则:
 *  - SyncMcpToolCallback → tool_type=2,MCP 工具
 *  - 其余 → tool_type=1,内置工具
 *
 * MCP 工具归属:provider 的 Bean 名为 mcpToolCallbackProvider-{serverCode},
 * 据此反查 ai_mcp_server 得到 mcp_server_id
 *
 * @author ruoyi
 */
@Service
public class ToolSyncService
{
    private static final Logger log = LoggerFactory.getLogger(ToolSyncService.class);

    /** DynamicMcpService 注册 MCP provider 的 Bean 名前缀 */
    public static final String MCP_PROVIDER_BEAN_PREFIX = "mcpToolCallbackProvider-";

    /** 内置工具中文名称映射(toolCode -> 中文名称) */
    private static final Map<String, String> BUILTIN_CN_NAMES = Map.ofEntries(
        Map.entry("getCurrentTime", "获取当前时间"),
        Map.entry("getSessionInfo", "查询会话信息"),
        Map.entry("read", "读取文件"),
        Map.entry("write", "写入文件"),
        Map.entry("edit", "编辑文件"),
        Map.entry("bash", "执行命令"),
        Map.entry("grep", "搜索文件内容"),
        Map.entry("find", "匹配文件路径"),
        Map.entry("ls", "列出目录"),
        Map.entry("createScheduledJob", "创建定时任务"),
        Map.entry("listScheduledJobs", "查询定时任务"),
        Map.entry("toggleScheduledJob", "启停定时任务"),
        Map.entry("deleteScheduledJob", "删除定时任务"),
        Map.entry("queryDeptList", "查询部门列表"),
        Map.entry("queryUserBrief", "查询用户简要")
    );

    @Autowired
    private ApplicationContext applicationContext;

    @Autowired
    private AiToolMapper aiToolMapper;

    @Autowired
    private AiAgentMapper aiAgentMapper;

    @Autowired
    private AiMcpServerMapper aiMcpServerMapper;

    /**
     * 工具索引(§6.2):同步完 DB 后必须刷新内存索引,否则
     * {@code AgentContextFactory.resolveTools} 按 toolCode 取不到新工具,
     * 智能体表现为"没有这个技能"。集中在此刷新,所有调用方(add MCP /
     * 编辑 MCP / 手动同步按钮 / 启动)都覆盖,避免漏调。
     */
    @Autowired
    private ToolCallbackRegistry toolCallbackRegistry;

    /** 生命周期锁:与启动异步同步/管理端连接操作串行,防交错改 Bean/表/索引 */
    @Autowired
    private ToolLifecycle toolLifecycle;

    /**
     * 同步全部(内置 + MCP)。启动时用;日常按钮不暴露。
     */
    public int syncAll()
    {
        toolLifecycle.lock().lock();
        try
        {
            log.info("开始同步所有工具(内置+MCP)");
            int count = syncProviders(false, null);
            log.info("所有工具同步完成,新增/复活 {} 个", count);
            toolCallbackRegistry.refresh();
            return count;
        }
        finally
        {
            toolLifecycle.lock().unlock();
        }
    }

    /**
     * 仅同步 MCP 工具(管理界面「同步 MCP」按钮)。
     * 内置工具启动时已同步,运行时再扫一遍没意义还浪费。
     */
    public int syncMcpOnly()
    {
        toolLifecycle.lock().lock();
        try
        {
            log.info("开始同步 MCP 工具");
            int count = syncProviders(true, null);
            log.info("MCP 工具同步完成,新增/复活 {} 个", count);
            toolCallbackRegistry.refresh();
            return count;
        }
        finally
        {
            toolLifecycle.lock().unlock();
        }
    }

    /**
     * 同步指定 MCP server 的工具。
     */
    public int syncMcp(Long mcpServerId)
    {
        if (mcpServerId == null)
        {
            return syncMcpOnly();
        }
        toolLifecycle.lock().lock();
        try
        {
            log.info("开始同步 MCP server [{}] 的工具", mcpServerId);
            int count = syncProviders(true, mcpServerId);
            log.info("MCP server [{}] 工具同步完成,新增/复活 {} 个", mcpServerId, count);
            toolCallbackRegistry.refresh();
            return count;
        }
        finally
        {
            toolLifecycle.lock().unlock();
        }
    }

    /**
     * @param mcpOnly       true 只处理 SyncMcpToolCallback
     * @param onlyServerId  非空时只处理该 MCP server 的 provider
     */
    private int syncProviders(boolean mcpOnly, Long onlyServerId)
    {
        int count = 0;
        Map<String, ToolCallbackProvider> providers =
            applicationContext.getBeansOfType(ToolCallbackProvider.class);
        for (Map.Entry<String, ToolCallbackProvider> entry : providers.entrySet())
        {
            String beanName = entry.getKey();
            if (mcpOnly && !beanName.startsWith(MCP_PROVIDER_BEAN_PREFIX))
            {
                continue;
            }
            if (onlyServerId != null)
            {
                Long sid = resolveMcpServerId(beanName);
                if (!onlyServerId.equals(sid))
                {
                    continue;
                }
            }
            ToolCallbackProvider provider = entry.getValue();
            ToolCallback[] callbacks;
            try
            {
                callbacks = provider.getToolCallbacks();
            }
            catch (Exception e)
            {
                log.warn("provider [{}] 获取工具失败: {}", beanName, e.getMessage());
                continue;
            }
            log.info("provider [{}] 返回 {} 个工具", beanName, callbacks.length);
            for (ToolCallback cb : callbacks)
            {
                try
                {
                    if (cb instanceof SyncMcpToolCallback)
                    {
                        if (upsertMcp(cb, resolveMcpServerId(beanName)))
                        {
                            count++;
                        }
                    }
                    else if (!mcpOnly && upsertBuiltin(cb))
                    {
                        count++;
                    }
                }
                catch (Exception e)
                {
                    String name;
                    try { name = cb.getToolDefinition().name(); }
                    catch (Exception ignore) { name = "?"; }
                    log.warn("同步工具 [{}] 失败,跳过: {}", name, e.getMessage());
                }
            }
        }
        return count;
    }

    /**
     * 删除指定 MCP server 名下的所有工具记录(物理删除)。
     * <p>删除 MCP server 时调用:server 已物理删除,工具必须随之清理,
     * 否则旧工具会一直留在 ai_tool 表(用户反馈"删了 MCP 工具还在")。
     * <p>删除前先清理 ai_agent_tool 里对这些工具的绑定,避免悬空绑定残留。
     * @param mcpServerId MCP 服务 ID
     * @return 删除的工具数
     */
    @Transactional
    public int removeMcpTools(Long mcpServerId)
    {
        toolLifecycle.lock().lock();
        try
        {
            // 先取该 MCP 名下全部工具 ID,清理 agent_tool 绑定,再物理删工具
            List<Long> toolIds = aiToolMapper.selectToolIdsByMcpServerId(mcpServerId);
            if (toolIds != null && !toolIds.isEmpty())
            {
                aiAgentMapper.deleteAgentToolByToolIds(toolIds.toArray(new Long[0]));
            }
            int n = aiToolMapper.deleteAiToolByMcpServerId(mcpServerId);
            log.info("清理 MCP server [{}] 名下工具,物理删除 {} 条", mcpServerId, n);
            toolCallbackRegistry.refresh();
            return n;
        }
        finally
        {
            toolLifecycle.lock().unlock();
        }
    }

    /**
     * 清理孤儿 MCP 工具:mcp_server_id 指向的 server 已不存在(被删除)的工具。
     * <p>启动自愈:历史上删除 MCP server 没清理工具(旧版 remove 是 TODO),
     * 这些工具重启后不会被 syncAll 重新 upsert(provider 已销毁),但仍残留在表里。
     * 启动时调一次,把历史脏数据清掉。
     * @return 软删的孤儿工具数
     */
    @Transactional
    public int purgeOrphanMcpTools()
    {
        toolLifecycle.lock().lock();
        try
        {
            java.util.Set<Long> liveServerIds = new java.util.HashSet<>(aiToolMapper.selectMcpServerIds());
            AiTool q = new AiTool();
            q.setToolType("2");
            List<AiTool> mcpTools = aiToolMapper.selectAiToolList(q);
            java.util.List<Long> orphanIds = new java.util.ArrayList<>();
            for (AiTool t : mcpTools)
            {
                if (t.getMcpServerId() == null || !liveServerIds.contains(t.getMcpServerId()))
                {
                    orphanIds.add(t.getToolId());
                }
            }
            int n = 0;
            if (!orphanIds.isEmpty())
            {
                // 物理删工具前先清理引用它们的 agent_tool 绑定,避免悬空
                aiAgentMapper.deleteAgentToolByToolIds(orphanIds.toArray(new Long[0]));
                n = aiToolMapper.deleteAiToolByIds(orphanIds.toArray(new Long[0]));
            }
            if (n > 0)
            {
                log.info("清理孤儿 MCP 工具,物理删除 {} 条(server 已不存在)", n);
            }
            return n;
        }
        finally
        {
            toolLifecycle.lock().unlock();
        }
    }

    public Map<String, Integer> stats()
    {
        Map<String, Integer> result = new HashMap<>();
        int providers = 0;
        int builtinCb = 0;
        int mcpCb = 0;
        for (Map.Entry<String, ToolCallbackProvider> e
            : applicationContext.getBeansOfType(ToolCallbackProvider.class).entrySet())
        {
            providers++;
            try
            {
                for (ToolCallback cb : e.getValue().getToolCallbacks())
                {
                    if (cb instanceof SyncMcpToolCallback)
                    {
                        mcpCb++;
                    }
                    else
                    {
                        builtinCb++;
                    }
                }
            }
            catch (Exception ignore) {}
        }
        result.put("providers", providers);
        result.put("builtinCallbacks", builtinCb);
        result.put("mcpTools", mcpCb);
        result.put("total", builtinCb + mcpCb);
        return result;
    }

    private boolean upsertBuiltin(ToolCallback cb)
    {
        String name = cb.getToolDefinition().name();
        String desc = clipDesc(cb.getToolDefinition().description());
        String inputSchema = safeSchema(cb);
        String cnName = BUILTIN_CN_NAMES.getOrDefault(name, name);
        // 含软删:MCP/内置删了再同步时 tool_code 仍占 uk_tool_code,必须复活
        AiTool exist = findByCodeIncludeDeleted(name);
        if (exist == null)
        {
            AiTool t = new AiTool();
            t.setToolCode(name);
            t.setToolName(cnName);
            t.setDescription(desc);
            t.setToolType("1");
            t.setCategory("内置");
            t.setInputSchema(inputSchema);
            t.setStatus("0");
            aiToolMapper.insertAiTool(t);
            log.info("新增内置工具: {} ({})", cnName, name);
            return true;
        }
        if (isDeleted(exist.getDelFlag()))
        {
            AiTool rev = new AiTool();
            rev.setToolId(exist.getToolId());
            rev.setToolName(cnName);
            rev.setDescription(desc);
            rev.setToolType("1");
            rev.setCategory("内置");
            rev.setInputSchema(inputSchema);
            aiToolMapper.reactivateAiTool(rev);
            log.info("复活内置工具: {} ({})", cnName, name);
            return true;
        }
        AiTool upd = new AiTool();
        upd.setToolId(exist.getToolId());
        // 仅当数据库中名称仍为英文方法名时才更新为中文名,保留用户手动修改
        if (name.equals(exist.getToolName()))
        {
            upd.setToolName(cnName);
        }
        // 仅当数据库中描述为空时才用代码注解的描述,保留用户手动修改
        if (!StringUtils.hasText(exist.getDescription()))
        {
            upd.setDescription(desc);
        }
        upd.setInputSchema(inputSchema);
        aiToolMapper.updateAiTool(upd);
        return false;
    }

    private boolean upsertMcp(ToolCallback cb, Long mcpServerId)
    {
        String name = cb.getToolDefinition().name();
        String desc = clipDesc(cb.getToolDefinition().description());
        String inputSchema = safeSchema(cb);
        // 含软删:MCP server 删了再加时,工具 tool_code 仍占 uk_tool_code
        AiTool exist = findByCodeIncludeDeleted(name);
        if (exist == null)
        {
            AiTool row = new AiTool();
            row.setToolCode(name);
            row.setToolName(name);
            row.setDescription(desc);
            row.setToolType("2");
            row.setCategory("MCP");
            row.setMcpServerId(mcpServerId);
            row.setRemoteToolName(name);
            row.setInputSchema(inputSchema);
            row.setStatus("0");
            aiToolMapper.insertAiTool(row);
            log.info("新增 MCP 工具: {} (mcpServerId={})", name, mcpServerId);
            return true;
        }
        if (isDeleted(exist.getDelFlag()))
        {
            AiTool rev = new AiTool();
            rev.setToolId(exist.getToolId());
            rev.setToolName(name);
            rev.setDescription(desc);
            rev.setToolType("2");
            rev.setCategory("MCP");
            rev.setMcpServerId(mcpServerId);
            rev.setRemoteToolName(name);
            rev.setInputSchema(inputSchema);
            aiToolMapper.reactivateAiTool(rev);
            log.info("复活 MCP 工具: {} (mcpServerId={})", name, mcpServerId);
            return true;
        }
        AiTool upd = new AiTool();
        upd.setToolId(exist.getToolId());
        // 仅当数据库中描述为空时才同步远端描述,保留用户手动修改的中文描述
        if (!StringUtils.hasText(exist.getDescription()))
        {
            upd.setDescription(desc);
        }
        upd.setInputSchema(inputSchema);
        // 同名本地工具切换到独立 MCP 后，不能只更新远端定位字段。
        // 否则记录仍是 tool_type=1（内置），MCP 管理页会将其排除，
        // 最终只显示那些原本不存在、被新插入的工具。
        upd.setToolType("2");
        upd.setCategory("MCP");
        upd.setMcpServerId(mcpServerId);
        upd.setRemoteToolName(name);
        aiToolMapper.updateAiTool(upd);
        return false;
    }

    /**
     * 软删标志判定:历史清理脚本写过 '1',删除接口约定 '2',两者都视为已删,统一走复活分支。
     */
    private boolean isDeleted(String delFlag)
    {
        return delFlag != null && !"0".equals(delFlag);
    }

    private String safeSchema(ToolCallback cb)
    {
        try
        {
            return String.valueOf(cb.getToolDefinition().inputSchema());
        }
        catch (Exception e)
        {
            return "";
        }
    }

    /**
     * 截断工具描述,防止远端返回的超长描述撑爆 description 列导致同步失败。
     * <p>description 列虽已改为 text,但保留上限防御:一是兼容尚未执行 DDL 的旧库
     * (仍为 varchar(500));二是避免异常远端返回超大文本。2000 字符足够 LLM 理解。
     */
    private static final int DESC_MAX_LEN = 2000;
    private String clipDesc(String desc)
    {
        if (desc == null)
        {
            return null;
        }
        return desc.length() > DESC_MAX_LEN ? desc.substring(0, DESC_MAX_LEN) : desc;
    }

    /**
     * 从 provider Bean 名解析 MCP server 归属
     */
    private Long resolveMcpServerId(String beanName)
    {
        if (beanName == null || !beanName.startsWith(MCP_PROVIDER_BEAN_PREFIX))
        {
            return null;
        }
        String serverCode = beanName.substring(MCP_PROVIDER_BEAN_PREFIX.length());
        AiMcpServer q = new AiMcpServer();
        q.setServerCode(serverCode);
        List<AiMcpServer> list = aiMcpServerMapper.selectAiMcpServerList(q);
        return list.isEmpty() ? null : list.get(0).getMcpServerId();
    }

    /**
     * 按 tool_code 精确查询(含软删)。同步 upsert 用,以便复活软删行。
     * <p>必须精确匹配:不能用 {@code selectAiToolList}(like 模糊)。
     */
    private AiTool findByCodeIncludeDeleted(String toolCode)
    {
        return aiToolMapper.selectAiToolByCodeIncludeDeleted(toolCode);
    }
}
