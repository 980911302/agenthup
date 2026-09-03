package com.ruoyi.system.tool;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.function.FunctionToolCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ruoyi.common.utils.StringUtils;

/**
 * 业务只读工具（部门/用户简要查询）。
 * <p>
 * 使用 JdbcTemplate 轻量查询，结果上限 20 条，仅返回摘要字段。
 * 在 {@code ToolConfig} 注册,由智能体勾选启用。
 *
 * @author ruoyi
 */
@Component
public class BusinessTools
{
    private static final Logger log = LoggerFactory.getLogger(BusinessTools.class);

    private static final int LIMIT = 20;

    private final JdbcTemplate jdbcTemplate;

    private final ObjectMapper objectMapper = new ObjectMapper();

    public BusinessTools(JdbcTemplate jdbcTemplate)
    {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 查询部门简要列表
     */
    public ToolCallback queryDeptListTool()
    {
        return FunctionToolCallback.builder("queryDeptList",
                (Function<QueryDeptRequest, String>) this::queryDeptList)
            .description("查询系统部门简要列表。可选 keyword 按部门名称模糊过滤。返回 deptId/deptName/parentId/status，最多 "
                + LIMIT + " 条。")
            .inputType(QueryDeptRequest.class)
            .build();
    }

    /**
     * 按用户名/昵称模糊查用户简要
     */
    public ToolCallback queryUserBriefTool()
    {
        return FunctionToolCallback.builder("queryUserBrief",
                (Function<QueryUserRequest, String>) this::queryUserBrief)
            .description("按用户名或昵称模糊查询用户简要信息。参数 keyword 必填。返回 userId/userName/nickName/dept，最多 "
                + LIMIT + " 条。")
            .inputType(QueryUserRequest.class)
            .build();
    }

    private String queryDeptList(QueryDeptRequest request)
    {
        try
        {
            String keyword = request != null ? request.keyword : null;
            List<Map<String, Object>> rows;
            if (StringUtils.isNotEmpty(keyword))
            {
                String like = "%" + keyword.trim() + "%";
                rows = jdbcTemplate.queryForList(
                    "select dept_id as deptId, dept_name as deptName, parent_id as parentId, status "
                        + "from sys_dept where del_flag = '0' and dept_name like ? "
                        + "order by parent_id, order_num limit ?",
                    like, LIMIT);
            }
            else
            {
                rows = jdbcTemplate.queryForList(
                    "select dept_id as deptId, dept_name as deptName, parent_id as parentId, status "
                        + "from sys_dept where del_flag = '0' "
                        + "order by parent_id, order_num limit ?",
                    LIMIT);
            }
            return objectMapper.writeValueAsString(rows);
        }
        catch (Exception e)
        {
            log.warn("queryDeptList 失败: {}", e.getMessage());
            return "查询部门失败: " + e.getMessage();
        }
    }

    private String queryUserBrief(QueryUserRequest request)
    {
        if (request == null || StringUtils.isEmpty(request.keyword))
        {
            return "参数 keyword 不能为空";
        }
        try
        {
            String like = "%" + request.keyword.trim() + "%";
            List<Map<String, Object>> raw = jdbcTemplate.queryForList(
                "select u.user_id, u.user_name, u.nick_name, d.dept_name "
                    + "from sys_user u left join sys_dept d on u.dept_id = d.dept_id "
                    + "where u.del_flag = '0' and (u.user_name like ? or u.nick_name like ?) "
                    + "order by u.user_id limit ?",
                like, like, LIMIT);
            List<Map<String, Object>> rows = new ArrayList<>();
            for (Map<String, Object> r : raw)
            {
                Map<String, Object> item = new HashMap<>(4);
                item.put("userId", r.get("user_id"));
                item.put("userName", r.get("user_name"));
                item.put("nickName", r.get("nick_name"));
                item.put("dept", r.get("dept_name") != null ? r.get("dept_name") : "");
                rows.add(item);
            }
            return objectMapper.writeValueAsString(rows);
        }
        catch (Exception e)
        {
            log.warn("queryUserBrief 失败: {}", e.getMessage());
            return "查询用户失败: " + e.getMessage();
        }
    }

    public static class QueryDeptRequest
    {
        @JsonProperty("keyword")
        @JsonPropertyDescription("可选，部门名称关键字")
        public String keyword;
    }

    public static class QueryUserRequest
    {
        @JsonProperty(value = "keyword", required = true)
        @JsonPropertyDescription("用户名或昵称关键字")
        public String keyword;
    }
}
