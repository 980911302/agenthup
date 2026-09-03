# 项目管理(桌面端会话分组)实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在桌面工作台增加「项目」作为会话的可选分组:项目有名称/描述,一个会话可归属一个项目,也可不归属(普通会话);新建对话时若选中项目则自动归属。

**Architecture:** 后端新增 `ai_project` 表与标准若依三层(Controller→Service→Mapper),`ai_chat_session` 加一列 `project_id`(null=未分组)。会话归属通过 `ChatRunCreateCommand` 新增可空 `projectId` 字段,在 `ChatRunService.ensureOwnedSession` 对**新建**会话补写 `project_id`(不改 `SessionAccessGuard.requireOrCreate` 签名,存量会话不覆盖)。删除项目级联复用现有 `deleteAiChatSessionById` 删除其下会话。前端 desktop 侧栏「项目」占位区落地为真实项目列表 + 过滤 + 新建 + 删除。

**Tech Stack:** Spring Boot 3.5.14 / Java 17 / MyBatis / MySQL 8;Vue 3 + Pinia + Element(desktop 自绘样式,无 UI 库)。

**Spec:** `docs/superpowers/specs/2026-08-30-project-management-design.md`

---

## 文件结构总览

**新增(后端)**
- `sql/ai_project.sql` — 建表 + `ai_chat_session` 加列/索引
- `ruoyi-system/src/main/java/com/ruoyi/system/domain/AiProject.java`
- `ruoyi-system/src/main/java/com/ruoyi/system/mapper/AiProjectMapper.java`
- `ruoyi-system/src/main/resources/mapper/system/AiProjectMapper.xml`(确认目录,见 Task 2)
- `ruoyi-system/src/main/java/com/ruoyi/system/service/IAiProjectService.java`
- `ruoyi-system/src/main/java/com/ruoyi/system/service/impl/AiProjectServiceImpl.java`
- `ruoyi-admin/src/main/java/com/ruoyi/web/controller/ai/AiProjectController.java`

**新增(前端)**
- `desktop/src/api/project.js`
- `desktop/src/components/ProjectDialog.vue`(新建/编辑项目弹窗)

**修改(后端)**
- `ruoyi-system/src/main/java/com/ruoyi/system/domain/AiChatSession.java` — 加 `projectId` 字段
- `ruoyi-system/src/main/resources/mapper/system/AiChatSessionMapper.xml` — resultMap/select/insert/update 加 `project_id` + `projectId` 过滤
- `ruoyi-system/src/main/java/com/ruoyi/system/ai/run/ChatRunCreateCommand.java` — 加 `projectId`
- `ruoyi-system/src/main/java/com/ruoyi/system/ai/run/ChatRunService.java` — `ensureOwnedSession` 补写归属
- `ruoyi-admin/src/main/java/com/ruoyi/web/controller/ai/dto/ChatRunRequest.java` — 加 `projectId`
- `ruoyi-admin/src/main/java/com/ruoyi/web/controller/ai/AiChatRunController.java` — 透传 `projectId`
- `ruoyi-admin/src/main/java/com/ruoyi/web/websocket/chat/ChatJsonRpcWebSocketHandler.java` — WS 路径透传 `projectId`(与 REST 保持一致)
- `ruoyi-system/src/main/java/com/ruoyi/system/ai/session/SessionAccessGuard.java` — `requireOrCreate` 返回"是否新建"的判定见 Task 4(不签名大改)

**修改(前端)**
- `desktop/src/api/chat.js` — `createChatRun` 无需改(data 直接透传)
- `desktop/src/composables/useChatRun.js` — `send` 已透传 payload,无需改(projectId 由 ChatView 注入 payload)
- `desktop/src/views/ChatView.vue` — 管理 projects 状态、newConversation 记住当前 projectId、发消息 payload 带 projectId、切项目过滤会话
- `desktop/src/components/SessionSidebar.vue` — 项目区落地:列表/新建/删除/选中过滤

---

## Task 1:SQL 脚本(建表 + 加列)

**Files:**
- Create: `sql/ai_project.sql`

- [ ] **Step 1: 创建 `sql/ai_project.sql`**

参照 `sql/ai_chat_session.sql` 的惯例(建表 + 索引 + 注释)。注意:新建库走 `sql/init/README.md` 顺序,此处脚本是增量 ALTER,与仓库 `ai_agent_appearance.sql` 等"加列脚本"同级。

```sql
-- ----------------------------
-- 项目表(ai_project):会话的可选分组容器
-- ----------------------------
drop table if exists ai_project;
create table ai_project (
  project_id    bigint(20)     not null auto_increment comment '项目ID',
  project_name  varchar(100)   not null                comment '项目名称',
  description   varchar(500)   default null            comment '项目描述',
  user_id       bigint(20)     default null            comment '归属用户ID(关联sys_user)',
  create_by     varchar(64)    default ''              comment '创建者',
  create_time   datetime                               comment '创建时间',
  update_by     varchar(64)    default ''              comment '更新者',
  update_time   datetime                               comment '更新时间',
  remark        varchar(500)   default null            comment '备注',
  del_flag      char(1)        default '0'             comment '删除标志(0存在 2删除)',
  primary key (project_id),
  key idx_user_id (user_id),
  key idx_create_time (create_time)
) engine=innodb comment = '项目表';

-- ----------------------------
-- 会话表加列:所属项目(空=未分组)
-- ----------------------------
alter table ai_chat_session
  add column project_id bigint(20) default null comment '所属项目ID(空=未分组)' after source_job_id;
alter table ai_chat_session add index idx_project_id (project_id);
```

- [ ] **Step 2: 校验脚本可执行(如本机有 MySQL)**

```bash
mysql -u root -p < sql/ai_project.sql
```
Expected: 无报错。若无本机 MySQL 可跳过(CI/手动环境执行)。

- [ ] **Step 3: Commit**

```bash
git add sql/ai_project.sql
git commit -m "feat: 项目表 ai_project 与会话 project_id 列"
```

---

## Task 2:后端 Domain + Mapper(项目)

**Files:**
- Create: `ruoyi-system/src/main/java/com/ruoyi/system/domain/AiProject.java`
- Create: `ruoyi-system/src/main/java/com/ruoyi/system/mapper/AiProjectMapper.java`
- Create: `ruoyi-system/src/main/resources/mapper/system/AiProjectMapper.xml`
- Modify(参照): `ruoyi-system/src/main/java/com/ruoyi/system/domain/AiChatSession.java`

- [ ] **Step 1: 确认 Mapper XML 目录**

```bash
find ruoyi-system/src/main/resources -name "AiChatSessionMapper.xml"
```
Expected: 输出路径(记下 Mapper XML 实际目录,Task 2 的 AiProjectMapper.xml 放同目录)。

- [ ] **Step 2: 写 AiProject domain**

```java
package com.ruoyi.system.domain;

import org.apache.commons.lang3.builder.ToStringBuilder;
import org.apache.commons.lang3.builder.ToStringStyle;
import com.ruoyi.common.core.domain.BaseEntity;

/**
 * 项目表 ai_project
 * <p>
 * 会话的可选分组容器:每个项目有名称与描述,一个会话可归属一个项目,
 * 也可不归属任何项目(普通会话)。项目仅对归属用户本人可见。
 */
public class AiProject extends BaseEntity
{
    private static final long serialVersionUID = 1L;

    /** 项目ID */
    private Long projectId;

    /** 项目名称 */
    private String projectName;

    /** 项目描述 */
    private String description;

    /** 归属用户ID(关联sys_user) */
    private Long userId;

    /** 项目下会话数(关联查询,非数据库列) */
    private Integer sessionCount;

    public Long getProjectId() { return projectId; }
    public void setProjectId(Long projectId) { this.projectId = projectId; }
    public String getProjectName() { return projectName; }
    public void setProjectName(String projectName) { this.projectName = projectName; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }
    public Integer getSessionCount() { return sessionCount; }
    public void setSessionCount(Integer sessionCount) { this.sessionCount = sessionCount; }

    @Override
    public String toString() {
        return new ToStringBuilder(this, ToStringStyle.MULTI_LINE_STYLE)
            .append("projectId", getProjectId())
            .append("projectName", getProjectName())
            .append("description", getDescription())
            .append("userId", getUserId())
            .append("createBy", getCreateBy())
            .append("createTime", getCreateTime())
            .append("updateBy", getUpdateBy())
            .append("updateTime", getUpdateTime())
            .append("remark", getRemark())
            .toString();
    }
}
```

- [ ] **Step 3: 写 AiProjectMapper 接口**

```java
package com.ruoyi.system.mapper;

import java.util.List;
import org.apache.ibatis.annotations.Param;
import com.ruoyi.system.domain.AiProject;

/** 项目表 ai_project 数据访问层 */
public interface AiProjectMapper
{
    /** 查询项目(含归属校验时用 projectId + userId) */
    public AiProject selectAiProjectById(Long projectId);

    /** 查询某用户的全部项目(按 create_time 倒序,带 session_count) */
    public List<AiProject> selectAiProjectList(AiProject aiProject);

    /** 新增项目 */
    public int insertAiProject(AiProject aiProject);

    /** 修改项目(动态 SET) */
    public int updateAiProject(AiProject aiProject);

    /** 删除项目(逻辑删除 del_flag='2') */
    public int deleteAiProjectById(Long projectId);

    /** 查询项目下全部会话ID(级联删除用) */
    public List<String> selectSessionIdsByProjectId(@Param("projectId") Long projectId);
}
```

- [ ] **Step 4: 写 AiProjectMapper.xml**

放在 Step 1 确认的 Mapper XML 目录。参照 `AiChatSessionMapper.xml` 的 resultMap / `<sql>` 片段风格。

```xml
<?xml version="1.0" encoding="UTF-8" ?>
<!DOCTYPE mapper
PUBLIC "-//mybatis.org//DTD Mapper 3.0//EN"
"http://mybatis.org/dtd/mybatis-3-mapper.dtd">
<mapper namespace="com.ruoyi.system.mapper.AiProjectMapper">

    <resultMap type="AiProject" id="AiProjectResult">
        <result property="projectId"    column="project_id"     />
        <result property="projectName"  column="project_name"   />
        <result property="description"  column="description"    />
        <result property="userId"       column="user_id"        />
        <result property="sessionCount" column="session_count"  />
        <result property="createBy"     column="create_by"      />
        <result property="createTime"   column="create_time"    />
        <result property="updateBy"     column="update_by"      />
        <result property="updateTime"   column="update_time"    />
        <result property="remark"       column="remark"         />
    </resultMap>

    <sql id="selectAiProjectVo">
        select p.project_id, p.project_name, p.description, p.user_id,
               p.create_by, p.create_time, p.update_by, p.update_time, p.remark,
               (select count(1) from ai_chat_session s
                 where s.project_id = p.project_id and s.del_flag = '0') as session_count
        from ai_project p
    </sql>

    <select id="selectAiProjectById" parameterType="Long" resultMap="AiProjectResult">
        <include refid="selectAiProjectVo"/>
        where p.del_flag = '0' and p.project_id = #{projectId}
    </select>

    <select id="selectAiProjectList" parameterType="AiProject" resultMap="AiProjectResult">
        <include refid="selectAiProjectVo"/>
        <where>
            p.del_flag = '0'
            <if test="userId != null">
                AND p.user_id = #{userId}
            </if>
            <if test="projectName != null and projectName != ''">
                AND p.project_name like concat('%', #{projectName}, '%')
            </if>
        </where>
        order by p.create_time desc
    </select>

    <insert id="insertAiProject" parameterType="AiProject" useGeneratedKeys="true" keyProperty="projectId">
        insert into ai_project
        <trim prefix="(" suffix=")" suffixOverrides=",">
            <if test="projectName != null and projectName != ''">project_name,</if>
            <if test="description != null">description,</if>
            <if test="userId != null">user_id,</if>
            <if test="createBy != null and createBy != ''">create_by,</if>
            <if test="createTime != null">create_time,</if>
            <if test="remark != null">remark,</if>
        </trim>
        <trim prefix="values (" suffix=")" suffixOverrides=",">
            <if test="projectName != null and projectName != ''">#{projectName},</if>
            <if test="description != null">#{description},</if>
            <if test="userId != null">#{userId},</if>
            <if test="createBy != null and createBy != ''">#{createBy},</if>
            <if test="createTime != null">#{createTime},</if>
            <if test="remark != null">#{remark},</if>
        </trim>
    </insert>

    <update id="updateAiProject" parameterType="AiProject">
        update ai_project
        <trim prefix="SET" suffixOverrides=",">
            <if test="projectName != null and projectName != ''">project_name = #{projectName},</if>
            <if test="description != null">description = #{description},</if>
            <if test="remark != null">remark = #{remark},</if>
            <if test="updateBy != null and updateBy != ''">update_by = #{updateBy},</if>
            update_time = sysdate()
        </trim>
        where project_id = #{projectId}
    </update>

    <update id="deleteAiProjectById" parameterType="Long">
        update ai_project set del_flag = '2' where project_id = #{projectId}
    </update>

    <select id="selectSessionIdsByProjectId" parameterType="Long" resultType="String">
        select session_id from ai_chat_session
        where project_id = #{projectId} and del_flag = '0'
    </select>

</mapper>
```

- [ ] **Step 5: 编译验证**

```bash
mvn -q -pl ruoyi-system -am compile
```
Expected: BUILD SUCCESS(无编译错误)。

- [ ] **Step 6: Commit**

```bash
git add ruoyi-system/src/main/java/com/ruoyi/system/domain/AiProject.java \
        ruoyi-system/src/main/java/com/ruoyi/system/mapper/AiProjectMapper.java \
        ruoyi-system/src/main/resources/mapper/system/AiProjectMapper.xml
git commit -m "feat: 项目 Domain 与 Mapper"
```

---

## Task 3:项目 Service 层

**Files:**
- Create: `ruoyi-system/src/main/java/com/ruoyi/system/service/IAiProjectService.java`
- Create: `ruoyi-system/src/main/java/com/ruoyi/system/service/impl/AiProjectServiceImpl.java`

- [ ] **Step 1: 写 IAiProjectService 接口**

```java
package com.ruoyi.system.service;

import java.util.List;
import com.ruoyi.system.domain.AiProject;

/**
 * 项目 服务层
 * <p>
 * 管理项目生命周期。项目仅对归属用户本人可见;删除项目时级联删除其下会话。
 */
public interface IAiProjectService
{
    /** 查询项目详情(调用方需先校验归属) */
    public AiProject selectAiProjectById(Long projectId);

    /** 查询某用户的全部项目 */
    public List<AiProject> selectAiProjectList(AiProject aiProject);

    /** 新增项目(返回主键) */
    public int insertAiProject(AiProject aiProject);

    /** 修改项目 */
    public int updateAiProject(AiProject aiProject);

    /** 删除项目(级联删除其下会话) */
    public int deleteAiProjectById(Long projectId);
}
```

- [ ] **Step 2: 写 AiProjectServiceImpl**

依赖 `AiProjectMapper`、`IAiChatSessionService`(级联删会话)、`SessionAccessGuard`(运行中拦截)。

```java
package com.ruoyi.system.service.impl;

import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.system.ai.session.SessionAccessGuard;
import com.ruoyi.system.domain.AiProject;
import com.ruoyi.system.mapper.AiProjectMapper;
import com.ruoyi.system.service.IAiChatSessionService;
import com.ruoyi.system.service.IAiProjectService;

/**
 * 项目 服务层实现
 */
@Service
public class AiProjectServiceImpl implements IAiProjectService
{
    @Autowired
    private AiProjectMapper aiProjectMapper;

    @Autowired
    private IAiChatSessionService aiChatSessionService;

    @Autowired
    private SessionAccessGuard sessionGuard;

    @Override
    public AiProject selectAiProjectById(Long projectId)
    {
        return aiProjectMapper.selectAiProjectById(projectId);
    }

    @Override
    public List<AiProject> selectAiProjectList(AiProject aiProject)
    {
        return aiProjectMapper.selectAiProjectList(aiProject);
    }

    @Override
    public int insertAiProject(AiProject aiProject)
    {
        return aiProjectMapper.insertAiProject(aiProject);
    }

    @Override
    public int updateAiProject(AiProject aiProject)
    {
        return aiProjectMapper.updateAiProject(aiProject);
    }

    /**
     * 删除项目(级联删除其下会话)。
     * <p>先校验项目存在且属于当前用户(由 Controller 传入的已校验对象或这里再查一次);
     * 对项目下每个会话先 requireNoActiveRun 拦截运行中,再复用
     * {@link IAiChatSessionService#deleteAiChatSessionById} 走完整的会话清理链。
     */
    @Override
    @Transactional
    public int deleteAiProjectById(Long projectId)
    {
        AiProject project = aiProjectMapper.selectAiProjectById(projectId);
        if (project == null)
        {
            throw new ServiceException("项目不存在");
        }
        List<String> sessionIds = aiProjectMapper.selectSessionIdsByProjectId(projectId);
        for (String sessionId : sessionIds)
        {
            sessionGuard.requireNoActiveRun(sessionId);
            aiChatSessionService.deleteAiChatSessionById(sessionId);
        }
        return aiProjectMapper.deleteAiProjectById(projectId);
    }
}
```

- [ ] **Step 3: 编译验证**

```bash
mvn -q -pl ruoyi-system -am compile
```
Expected: BUILD SUCCESS。

- [ ] **Step 4: Commit**

```bash
git add ruoyi-system/src/main/java/com/ruoyi/system/service/IAiProjectService.java \
        ruoyi-system/src/main/java/com/ruoyi/system/service/impl/AiProjectServiceImpl.java
git commit -m "feat: 项目 Service 层(含级联删会话)"
```

---

## Task 4:会话表 project_id 全链路(后端)

**Files:**
- Modify: `ruoyi-system/src/main/java/com/ruoyi/system/domain/AiChatSession.java`
- Modify: `ruoyi-system/src/main/resources/mapper/system/AiChatSessionMapper.xml`
- Modify: `ruoyi-system/src/main/java/com/ruoyi/system/ai/run/ChatRunCreateCommand.java`
- Modify: `ruoyi-system/src/main/java/com/ruoyi/system/ai/run/ChatRunService.java`
- Modify: `ruoyi-admin/src/main/java/com/ruoyi/web/controller/ai/dto/ChatRunRequest.java`
- Modify: `ruoyi-admin/src/main/java/com/ruoyi/web/controller/ai/AiChatRunController.java`
- Modify: `ruoyi-admin/src/main/java/com/ruoyi/web/websocket/chat/ChatJsonRpcWebSocketHandler.java`
- Modify: `ruoyi-system/src/main/java/com/ruoyi/system/ai/job/AiJobDispatcher.java`(第 196 行构造点,传 projectId=null)

- [ ] **Step 1: Domain 加 projectId 字段**

在 `AiChatSession.java` 的 `sourceJobId` 之后加字段与 getter/setter:

```java
    /** 所属项目ID(空=未分组) */
    private Long projectId;

    public Long getProjectId() { return projectId; }
    public void setProjectId(Long projectId) { this.projectId = projectId; }
```

- [ ] **Step 2: Mapper XML 同步 project_id**

(a) `resultMap` 里加:`<result property="projectId" column="project_id"/>`
(b) `selectAiChatSessionVo` 的列里加 `s.project_id,`
(c) `selectAiChatSessionByIdForUpdate` 的列里加 `project_id,`
(d) `selectAiChatSessionList` 的 `<where>` 加过滤:

```xml
<if test="projectId != null">
    AND s.project_id = #{projectId}
</if>
```

(e) **关键**:`insertAiChatSessionIfAbsent` 是**固定列清单**的 insert ignore(第 142-150 行)。在建行时带 `project_id`,需把 `project_id` 列加进列清单,值用 `#{projectId}`(null 即插入 NULL = 未分组):

```xml
<insert id="insertAiChatSessionIfAbsent" parameterType="AiChatSession">
    insert ignore into ai_chat_session
    (session_id, title, session_type, source_job_id, project_id, user_id, status, total_tokens, context_length, create_by, create_time)
    values
    (#{sessionId}, #{title},
     ifnull(#{sessionType}, 'chat'), #{sourceJobId}, #{projectId},
     #{userId}, #{status}, #{totalTokens}, #{contextLength},
     #{createBy}, current_timestamp)
</insert>
```

(f) `updateAiChatSession` 的 SET trim 加(供后续归属调整能力复用,本计划 Task 4 走 insert 落库,不依赖它,但加上保持字段完整):

```xml
<if test="projectId != null">project_id = #{projectId},</if>
```

> 说明:`insertAiChatSession`(另一条动态 trim 的 insert)本次不动,`requireOrCreate` 只走 `insertAiChatSessionIfAbsent`。

- [ ] **Step 3: ChatRunCreateCommand 加 projectId**

record 在**现有最后两个字段(modelId, skillIds)之后**再加一个可空字段。⚠️ 注意:工作区有并行 WIP,`ChatRunCreateCommand` 当前已是 **11 参**(含 `modelId`、`skillIds`),不要按本文件旧示例的 10 参来改,以当前文件实际内容为准,只在末尾追加 `Long projectId`:

```java
public record ChatRunCreateCommand(
        String sessionId,
        Long agentId,
        String message,
        List<ChatRunAttachment> attachments,
        String clientRequestId,
        Long userId,
        String username,
        boolean admin,
        List<Long> kbIds,
        Long modelId,
        List<Long> skillIds,
        Long projectId)   // ← 新增,可空
{
    public ChatRunCreateCommand
    {
        attachments = attachments == null ? List.of() : List.copyOf(attachments);
        // null 表示前端未传(不动会话已有知识库)；空列表表示清空；其余防可变。
        kbIds = kbIds == null ? null : List.copyOf(kbIds);
        skillIds = skillIds == null ? List.of() : List.copyOf(skillIds);
    }
}
```

- [ ] **Step 4: ChatRunService 透传 projectId 到建行**

归属在建行时由 `requireOrCreate` 写入(Step 2 的 insert ignore 已带 project_id 列)。`ChatRunService.ensureOwnedSession` 调用新重载,把 `request.projectId()` 传入;存量会话 insert ignore 不生效,天然不覆盖。

(a) `SessionAccessGuard.requireOrCreate` 增加重载(原 6 参方法保留委托,不影响其它调用方):

```java
    public AiChatSession requireOrCreate(String sessionId, Long userId, String username, boolean admin,
                                         String title, String sessionType)
    {
        return requireOrCreate(sessionId, userId, username, admin, title, sessionType, null);
    }

    public AiChatSession requireOrCreate(String sessionId, Long userId, String username, boolean admin,
                                         String title, String sessionType, Long projectId)
    {
        // ... 原有校验逻辑不变 ...
        AiChatSession candidate = new AiChatSession();
        candidate.setSessionId(id);
        candidate.setTitle(title == null ? "" : title);
        candidate.setSessionType(sessionType);
        candidate.setUserId(userId);
        candidate.setStatus("0");
        candidate.setTotalTokens(0L);
        candidate.setContextLength(0L);
        candidate.setCreateBy(username);
        candidate.setProjectId(projectId);   // 仅新建生效;存量 insert ignore 不覆盖
        sessionMapper.insertAiChatSessionIfAbsent(candidate);
        // ... 原有重读与 checkOwnership 不变 ...
    }
```

(b) `ChatRunService.ensureOwnedSession` 里把 `request.projectId()` 传进重载:

```java
        sessionGuard.requireOrCreate(request.sessionId(), request.userId(), request.username(),
                request.admin(), truncateTitle(request.message()), null, request.projectId());
```

> 其余逻辑(title 补写、ensureAgentJoined)保持不变。

- [ ] **Step 5: ChatRunRequest DTO 加 projectId**

```java
    /** 所属项目ID(新建会话时可选;null=普通会话不归属) */
    private Long projectId;

    public Long getProjectId() { return projectId; }
    public void setProjectId(Long projectId) { this.projectId = projectId; }
```

- [ ] **Step 6: AiChatRunController 透传**

工作区 `AiChatRunController.create` 当前构造已带 `request.getModelId(), request.getSkillIds()`(11 参)。在其后再加一个 `request.getProjectId()`:

```java
            AiChatRun run = runService.create(new ChatRunCreateCommand(
                    request.getSessionId(), request.getAgentId(), request.getMessage(),
                    convertAttachments(request.getAttachments()), request.getClientRequestId(),
                    getUserId(), getUsername(), isAdmin(), request.getKbIds(),
                    request.getModelId(), request.getSkillIds(),
                    request.getProjectId()));
```

- [ ] **Step 7: WebSocket handler 透传(保持双路径一致)**

`ChatJsonRpcWebSocketHandler` 的 run 构造当前已带 `params.getLong("modelId"), toLongList(params.get("skillIds"), "skillIds")`(11 参)。在其后再加 `params.getLong("projectId")`(可空 Long):

```java
        return DesktopViews.run(runService.create(new ChatRunCreateCommand(
                requiredString(params, "sessionId"), params.getLong("agentId"),
                params.getString("message"), attachments, params.getString("clientRequestId"),
                userId, username, admin, kbIds, params.getLong("modelId"),
                toLongList(params.get("skillIds"), "skillIds"),
                params.getLong("projectId"))));
```

- [ ] **Step 7b: AiJobDispatcher 构造点补参(job 会话不归属)**

`AiJobDispatcher.java` 的 `new ChatRunCreateCommand(...)` 构造当前末尾是 `null, null`(modelId/skillIds)。再补一个 `null`(projectId,job 会话不归属):

```java
            AiChatRun run = chatRunService.create(new ChatRunCreateCommand(
                    sessionId,
                    job.getAgentId(),
                    job.getPrompt(),
                    attachments,
                    clientRequestId,
                    job.getOwnerUserId(),
                    ownerUsername,
                    false,
                    null,
                    null,
                    null,
                    null));   // ← 追加的 projectId = null
```

这样 job 会话走 `ensureOwnedSession` 时 `request.projectId() == null`,`requireOrCreate` 建行时 project_id 为 NULL,行为与现状完全一致。

- [ ] **Step 8: 编译验证**

```bash
mvn -q -pl ruoyi-system -am compile && mvn -q -pl ruoyi-admin -am compile
```
Expected: BUILD SUCCESS。

- [ ] **Step 9: Commit**

```bash
git add ruoyi-system/src/main/java/com/ruoyi/system/domain/AiChatSession.java \
        ruoyi-system/src/main/resources/mapper/system/AiChatSessionMapper.xml \
        ruoyi-system/src/main/java/com/ruoyi/system/ai/run/ChatRunCreateCommand.java \
        ruoyi-system/src/main/java/com/ruoyi/system/ai/run/ChatRunService.java \
        ruoyi-admin/src/main/java/com/ruoyi/web/controller/ai/dto/ChatRunRequest.java \
        ruoyi-admin/src/main/java/com/ruoyi/web/controller/ai/AiChatRunController.java \
        ruoyi-admin/src/main/java/com/ruoyi/web/websocket/chat/ChatJsonRpcWebSocketHandler.java
git commit -m "feat: 会话归属项目 project_id 全链路透传"
```

---

## Task 5:项目 Controller(CRUD + 级联删)

**Files:**
- Create: `ruoyi-admin/src/main/java/com/ruoyi/web/controller/ai/AiProjectController.java`

- [ ] **Step 1: 写 AiProjectController**

参照 `AiChatSessionController` 的 BaseController 风格。归属校验:list 强制 `userId = getUserId()`;detail/update/delete 先查项目并校验 `user_id == getUserId()`,不匹配抛"项目不存在"。

```java
package com.ruoyi.web.controller.ai;

import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import com.ruoyi.common.annotation.Log;
import com.ruoyi.common.core.controller.BaseController;
import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.common.core.page.TableDataInfo;
import com.ruoyi.common.enums.BusinessType;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.common.utils.SecurityUtils;
import com.ruoyi.common.utils.StringUtils;
import com.ruoyi.system.domain.AiProject;
import com.ruoyi.system.service.IAiProjectService;

/**
 * 项目 信息操作处理
 *
 * <p>桌面端会话分组的可选容器,仅对归属用户本人开放(不挂按钮级权限点,
 * 由 Controller 内校验 user_id)。
 */
@RestController
@RequestMapping("/ai/project")
public class AiProjectController extends BaseController
{
    @Autowired
    private IAiProjectService aiProjectService;

    /**
     * 查询当前用户的项目列表(带 session_count,按 create_time 倒序)
     */
    @GetMapping("/list")
    public TableDataInfo list(AiProject aiProject)
    {
        aiProject.setUserId(getUserId());
        startPage();
        List<AiProject> list = aiProjectService.selectAiProjectList(aiProject);
        return getDataTable(list);
    }

    /**
     * 查询项目详情
     */
    @GetMapping("/{projectId}")
    public AjaxResult getInfo(@PathVariable Long projectId)
    {
        return success(requireOwned(projectId));
    }

    /**
     * 新建项目
     */
    @Log(title = "AI项目", businessType = BusinessType.INSERT)
    @PostMapping
    public AjaxResult add(@Validated @RequestBody AiProject project)
    {
        if (StringUtils.isEmpty(project.getProjectName()))
        {
            throw new ServiceException("项目名称不能为空");
        }
        project.setUserId(getUserId());
        project.setCreateBy(getUsername());
        return toAjax(aiProjectService.insertAiProject(project));
    }

    /**
     * 修改项目
     */
    @Log(title = "AI项目", businessType = BusinessType.UPDATE)
    @PutMapping("/{projectId}")
    public AjaxResult edit(@PathVariable Long projectId, @Validated @RequestBody AiProject project)
    {
        requireOwned(projectId);
        if (StringUtils.isEmpty(project.getProjectName()))
        {
            throw new ServiceException("项目名称不能为空");
        }
        project.setProjectId(projectId);
        project.setUpdateBy(getUsername());
        return toAjax(aiProjectService.updateAiProject(project));
    }

    /**
     * 删除项目(级联删除其下会话)
     */
    @Log(title = "AI项目", businessType = BusinessType.DELETE)
    @DeleteMapping("/{projectId}")
    public AjaxResult remove(@PathVariable Long projectId)
    {
        requireOwned(projectId);
        return toAjax(aiProjectService.deleteAiProjectById(projectId));
    }

    /** 校验项目存在且属于当前用户,返回项目对象。 */
    private AiProject requireOwned(Long projectId)
    {
        AiProject project = aiProjectService.selectAiProjectById(projectId);
        if (project == null || !project.getUserId().equals(getUserId()))
        {
            throw new ServiceException("项目不存在");
        }
        return project;
    }
}
```

> 注意:`selectAiProjectList` 里 `session_count` 是子查询计算的非表列,list 用 `TableDataInfo` 分页包 `AiProject` 即可,无需改分页逻辑。若 `getUsername()` 不存在于 BaseController,用 `SecurityUtils.getUsername()`(以 `AiChatSessionController` 实际写法为准)。

- [ ] **Step 2: 编译验证**

```bash
mvn -q -pl ruoyi-admin -am compile
```
Expected: BUILD SUCCESS。

- [ ] **Step 3: Commit**

```bash
git add ruoyi-admin/src/main/java/com/ruoyi/web/controller/ai/AiProjectController.java
git commit -m "feat: 项目管理接口 /ai/project"
```

---

## Task 6:后端单测(项目 Service)

**Files:**
- Create: `ruoyi-system/src/test/java/com/ruoyi/system/service/AiProjectServiceTest.java`

> 参照现有 `ruoyi-system/src/test/java/com/ruoyi/system/service/AiAgentUpdatesTest.java` 的测试风格(Mockito,不依赖 DB 时用 mock;若现有测试走真实 mapper,则本测试聚焦 Service 级联逻辑,用 mock 隔离 mapper 与 sessionService)。

- [ ] **Step 1: 写测试**

```java
package com.ruoyi.system.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.system.ai.session.SessionAccessGuard;
import com.ruoyi.system.domain.AiProject;
import com.ruoyi.system.mapper.AiProjectMapper;
import com.ruoyi.system.service.impl.AiProjectServiceImpl;

/**
 * 项目 Service 单元测试:级联删会话、运行中拦截、项目不存在。
 */
@ExtendWith(MockitoExtension.class)
class AiProjectServiceTest
{
    @Mock private AiProjectMapper aiProjectMapper;
    @Mock private IAiChatSessionService aiChatSessionService;
    @Mock private SessionAccessGuard sessionGuard;

    @InjectMocks private AiProjectServiceImpl service;

    private AiProject project;

    @BeforeEach
    void setUp()
    {
        project = new AiProject();
        project.setProjectId(1L);
        project.setProjectName("测试项目");
        project.setUserId(100L);
    }

    @Test
    void delete_cascadesSessions()
    {
        when(aiProjectMapper.selectAiProjectById(1L)).thenReturn(project);
        when(aiProjectMapper.selectSessionIdsByProjectId(1L)).thenReturn(List.of("s1", "s2"));

        int rows = service.deleteAiProjectById(1L);

        verify(aiChatSessionService).deleteAiChatSessionById("s1");
        verify(aiChatSessionService).deleteAiChatSessionById("s2");
        verify(aiProjectMapper).deleteAiProjectById(1L);
        assertThat(rows).isEqualTo(1);
    }

    @Test
    void delete_projectNotExist_throws()
    {
        when(aiProjectMapper.selectAiProjectById(99L)).thenReturn(null);

        assertThatThrownBy(() -> service.deleteAiProjectById(99L))
                .isInstanceOf(ServiceException.class)
                .hasMessageContaining("项目不存在");
    }
}
```

> 运行中拦截(`sessionGuard.requireNoActiveRun` 抛 `ServiceException`)在 mock 下通过 `doThrow` 验证,可加第三个测试;若拦截逻辑在 `requireNoActiveRun` 内部,Service 侧只需保证调用它即可,测试断言 `verify(sessionGuard).requireNoActiveRun("s1")`。

- [ ] **Step 2: 运行测试**

```bash
mvn test -pl ruoyi-system -am -Dtest=AiProjectServiceTest -Dsurefire.failIfNoSpecifiedTests=false
```
Expected: 2 个测试通过(BUILD SUCCESS)。

- [ ] **Step 3: Commit**

```bash
git add ruoyi-system/src/test/java/com/ruoyi/system/service/AiProjectServiceTest.java
git commit -m "test: 项目 Service 级联删会话单测"
```

---

## Task 7:前端 API + 项目弹窗组件

**Files:**
- Create: `desktop/src/api/project.js`
- Create: `desktop/src/components/ProjectDialog.vue`

- [ ] **Step 1: 写 `desktop/src/api/project.js`**

参照 `desktop/src/api/session.js` 的 request 封装。

```js
import request from './request'

/** 我的项目列表(带 session_count) */
export function listProject(query) {
  return request({ url: '/ai/project/list', method: 'get', params: query })
}

/** 新建项目 */
export function createProject(data) {
  return request({ url: '/ai/project', method: 'post', data })
}

/** 修改项目 */
export function updateProject(projectId, data) {
  return request({ url: '/ai/project/' + projectId, method: 'put', data })
}

/** 删除项目(级联删其下会话) */
export function deleteProject(projectId) {
  return request({ url: '/ai/project/' + projectId, method: 'delete' })
}
```

- [ ] **Step 2: 写 `ProjectDialog.vue`**

自绘样式弹窗(与现有 AppConfirm/AppToast 轻量组件一致,不用 UI 库)。支持「新建」与「编辑」两种模式。

```vue
<script setup>
import { ref } from 'vue'

const props = defineProps({
  visible: { type: Boolean, default: false },
  // 编辑模式传入;新建传 null
  project: { type: Object, default: null }
})

const emit = defineEmits(['close', 'save'])

const name = ref('')
const description = ref('')

function reset() {
  name.value = props.project?.projectName || ''
  description.value = props.project?.description || ''
}

function onOpen() {
  reset()
}

function submit() {
  if (!name.value.trim()) return
  emit('save', {
    projectId: props.project?.projectId,
    projectName: name.value.trim(),
    description: description.value.trim()
  })
}
</script>

<template>
  <div v-if="visible" class="project-dialog-mask" @click.self="emit('close')">
    <div class="project-dialog">
      <div class="project-dialog__head">
        <strong>{{ project ? '编辑项目' : '新建项目' }}</strong>
        <button type="button" class="project-dialog__x" @click="emit('close')">✕</button>
      </div>
      <label class="project-dialog__field">
        <span>项目名称</span>
        <input v-model="name" placeholder="请输入项目名称" maxlength="100" />
      </label>
      <label class="project-dialog__field">
        <span>项目描述</span>
        <textarea v-model="description" placeholder="可选,描述这个项目做什么" maxlength="500" rows="3" />
      </label>
      <div class="project-dialog__foot">
        <button type="button" class="project-dialog__cancel" @click="emit('close')">取消</button>
        <button type="button" class="project-dialog__ok" @click="submit">保存</button>
      </div>
    </div>
  </div>
</template>

<style scoped>
.project-dialog-mask {
  position: fixed; inset: 0; z-index: 100;
  background: rgba(0, 0, 0, 0.45);
  display: flex; align-items: center; justify-content: center;
}
.project-dialog {
  width: 360px; border-radius: 12px;
  background: var(--bg-raised);
  border: 1px solid var(--border);
  box-shadow: var(--shadow-card, 0 10px 30px rgba(0,0,0,0.2));
  padding: 16px;
}
.project-dialog__head {
  display: flex; justify-content: space-between; align-items: center; margin-bottom: 14px;
}
.project-dialog__x { border: none; background: transparent; color: var(--text-tertiary); cursor: pointer; }
.project-dialog__field { display: flex; flex-direction: column; gap: 6px; margin-bottom: 12px; font-size: 12.5px; color: var(--text-secondary); }
.project-dialog__field input, .project-dialog__field textarea {
  border: 1px solid var(--border); border-radius: 8px; padding: 8px 10px;
  background: var(--bg-input, var(--bg)); color: var(--text); font-family: inherit; font-size: 13px; outline: none;
}
.project-dialog__field input:focus, .project-dialog__field textarea:focus { border-color: var(--accent); box-shadow: 0 0 0 2px var(--accent-weak); }
.project-dialog__foot { display: flex; justify-content: flex-end; gap: 8px; margin-top: 14px; }
.project-dialog__cancel {
  border: 1px solid var(--border); background: transparent; color: var(--text-secondary);
  border-radius: 8px; padding: 7px 14px; cursor: pointer;
}
.project-dialog__ok {
  border: none; background: var(--accent); color: #fff; border-radius: 8px; padding: 7px 16px; cursor: pointer;
}
</style>
```

> 注意:组件挂载时若 `visible` 初始为 true 不会触发 `onOpen`,需在父级用 `v-if="dialogVisible"` 控制挂载(见 Task 8),或在模板用 `v-if="visible"` 包内部再触发 reset。简单起见,Task 8 用 `v-if` 挂载,`reset()` 在 `onMounted`/`onOpen` 里执行一次即可(组件每次挂载都是新状态)。

- [ ] **Step 3: Commit**

```bash
git add desktop/src/api/project.js desktop/src/components/ProjectDialog.vue
git commit -m "feat: 前端项目 API 与新建/编辑弹窗"
```

---

## Task 8:桌面端侧栏项目区 + 会话归属(前端)

**Files:**
- Modify: `desktop/src/views/ChatView.vue`
- Modify: `desktop/src/components/SessionSidebar.vue`

- [ ] **Step 1: ChatView 接入项目状态**

在 `ChatView.vue` 的 script 里:

(a) import:
```js
import { deleteProject, listProject } from '../api/project'
import ProjectDialog from '../components/ProjectDialog.vue'
```

(b) 状态与函数(放在 `sessions` 相关逻辑附近):

```js
const projects = ref([])
const projectLoading = ref(false)
const selectedProjectId = ref(null)   // 侧栏当前选中的项目(null=全部/未分组视图)
const projectDialogVisible = ref(false)
const editingProject = ref(null)      // null=新建

async function loadProjects() {
  projectLoading.value = true
  try {
    const res = await listProject({ pageNum: 1, pageSize: 100 })
    projects.value = res.rows || []
  } catch (_) {
    projects.value = []
  } finally {
    projectLoading.value = false
  }
}

function openCreateProject() {
  editingProject.value = null
  projectDialogVisible.value = true
}

function openEditProject(p) {
  editingProject.value = p
  projectDialogVisible.value = true
}

async function saveProject(data) {
  try {
    if (data.projectId) await updateProject(data.projectId, data)
    else await createProject(data)
    await loadProjects()
    projectDialogVisible.value = false
  } catch (e) {
    toast(e.message || '保存失败')
  }
}

async function removeProject(p) {
  const ok = await confirmDanger(
    '删除项目',
    `确定删除项目「${p.projectName}」吗？该项目下的所有会话将被级联删除,此操作不可撤销。`,
    { okLabel: '删除', cancelLabel: '取消' }
  )
  if (!ok) return
  try {
    await deleteProject(p.projectId)
    await loadProjects()
    if (selectedProjectId.value === p.projectId) {
      selectedProjectId.value = null
    }
    await loadSessions()
  } catch (e) {
    toast(e.message || '删除失败')
  }
}

function selectProject(projectId) {
  selectedProjectId.value = projectId === selectedProjectId.value ? null : projectId
  loadSessions()
}
```

(c) `newConversation` 记录归属(新建时用当前选中项目):

```js
function newConversation() {
  conversationId.value = genId()
  sessionPersisted.value = false
  chat.setKbIds([])
  run.setTurns([])
  timelineHasMore.value = false
  contextUsage.value = null
  // 当前选中项目 → 新建对话归属;未选则普通会话
  pendingProjectId.value = selectedProjectId.value || null
}
```
顶部加 `const pendingProjectId = ref(null)`。

(d) `sendMessage` 的 payload 带上 projectId:

```js
  const payload = {
    sessionId: conversationId.value,
    agentId: chat.agentId,
    message: text,
    kbIds: kbIds
  }
  if (pendingProjectId.value) payload.projectId = pendingProjectId.value
```

(e) `loadSessions` 传 projectId 过滤:

```js
async function loadSessions() {
  try {
    const res = await listSession({
      pageNum: 1, pageSize: 100,
      projectId: selectedProjectId.value || undefined
    })
    sessions.value = res.rows || []
    // ... 原有 lastId 逻辑;注意切项目时不要强切回历史 lastId
  } catch (_) {
    sessions.value = []
  }
}
```

> 细节:切换项目时调用 `loadSessions()` 会触发"恢复 lastId/首条会话"的原有逻辑。为避免切项目后被历史会话强拉,可在 `selectProject` 里先 `newConversation()` 清空当前会话再 `loadSessions()`(推荐),或给 `loadSessions` 加一个"跳过自动恢复"参数。计划采用:选中项目时 `newConversation()` + `loadSessions()`。

(f) 模板:在 `SessionSidebar` 加绑定与事件,并挂载 `ProjectDialog`:

```html
    <SessionSidebar
      :sessions="sessions"
      :current-session-id="conversationId"
      :loading="sessionLoading"
      :streaming="streaming"
      :user="auth.user"
      :collapsed="sidebarCollapsed"
      :projects="projects"
      :selected-project-id="selectedProjectId"
      @new="newConversation"
      @switch="switchSession"
      @delete="deleteSession"
      @logout="doLogout"
      @settings="openSettings('profile')"
      @open-kb="kbOpen = true"
      @open-workspace="workspaceOpen = true"
      @toggle-collapse="sidebarCollapsed = !sidebarCollapsed"
      @create-project="openCreateProject"
      @edit-project="openEditProject"
      @delete-project="removeProject"
      @select-project="selectProject"
    />
    ...
    <ProjectDialog
      v-if="projectDialogVisible"
      :project="editingProject"
      @close="projectDialogVisible = false"
      @save="saveProject"
    />
```

- [ ] **Step 2: 侧栏项目区落地**

在 `SessionSidebar.vue`:

(a) props 加:
```js
  projects: { type: Array, default: () => [] },
  selectedProjectId: { type: Number, default: null }
```

(b) emits 加:
```js
  'create-project', 'edit-project', 'delete-project', 'select-project'
```

(c) 替换占位区块(第 170-177 行 `sidebar__projects-head` / `sidebar__projects-empty`):

```html
      <div class="sidebar__projects-head">
        <span>项目</span>
        <button type="button" class="sidebar__projects-add" title="新建项目" @click="emit('create-project')">
          <svg width="12" height="12" viewBox="0 0 16 16" fill="none"><path d="M8 3v10M3 8h10" stroke="currentColor" stroke-width="1.8" stroke-linecap="round"/></svg>
        </button>
      </div>
      <div class="sidebar__projects">
        <div
          v-for="p in projects"
          :key="p.projectId"
          class="sidebar__project"
          :class="{ active: p.projectId === selectedProjectId }"
          @click="emit('select-project', p.projectId)"
        >
          <span class="sidebar__project-name">{{ p.projectName }}</span>
          <span class="sidebar__project-meta">
            <span class="sidebar__project-count">{{ p.sessionCount ?? 0 }}</span>
            <button
              type="button"
              class="sidebar__project-del"
              title="删除项目"
              @click.stop="emit('delete-project', p)"
            >
              <svg width="12" height="12" viewBox="0 0 16 16" fill="none"><path d="M4 4l8 8M12 4l-8 8" stroke="currentColor" stroke-width="1.5" stroke-linecap="round"/></svg>
            </button>
            <button
              type="button"
              class="sidebar__project-edit"
              title="编辑项目"
              @click.stop="emit('edit-project', p)"
            >
              <svg width="12" height="12" viewBox="0 0 16 16" fill="none"><path d="M11 2l3 3-7 7H4v-3l7-7z" stroke="currentColor" stroke-width="1.4" stroke-linecap="round" stroke-linejoin="round"/></svg>
            </button>
          </span>
        </div>
        <div v-if="!projects.length" class="sidebar__projects-empty">暂无项目,点 + 新建。</div>
      </div>
```

(d) 补充样式(加到 scoped style):

```scss
  &__projects {
    padding: 0 12px 8px;
    display: flex;
    flex-direction: column;
    gap: 2px;
  }
  &__project {
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: 6px;
    padding: 6px 10px;
    border-radius: 8px;
    font-size: 13px;
    color: var(--text-secondary);
    cursor: pointer;

    &:hover { background: var(--bg-hover); color: var(--text); }
    &.active {
      background: color-mix(in srgb, var(--accent) 8%, var(--bg-raised));
      color: var(--accent);
      font-weight: 600;
    }
    &-name { flex: 1; min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
    &-meta { display: flex; align-items: center; gap: 4px; flex-shrink: 0; }
    &-count { font-size: 11px; color: var(--text-tertiary); font-variant-numeric: tabular-nums; }
    &-del, &-edit {
      opacity: 0; width: 18px; height: 18px; border: none; border-radius: 4px;
      background: transparent; color: var(--text-tertiary);
      display: flex; align-items: center; justify-content: center; cursor: pointer;
      &:hover { background: var(--danger-weak); color: var(--danger-text); }
    }
    &-edit:hover { background: var(--bg-hover); color: var(--text); }
    &:hover .sidebar__project-del, &:hover .sidebar__project-edit { opacity: 1; }
  }
```

> `emit` 已在 setup 中由 `defineEmits` 提供,模板内直接用 `emit('...')`(现有代码即如此)。

- [ ] **Step 3: 前端构建验证**

```bash
cd desktop && pnpm build
```
Expected: build 成功,无语法/引用错误。

- [ ] **Step 4: Commit**

```bash
git add desktop/src/views/ChatView.vue desktop/src/components/SessionSidebar.vue
git commit -m "feat: 桌面端侧栏项目区与会话归属"
```

---

## Task 9:回归与收尾

**Files:**
- 无新增

- [ ] **Step 1: 后端全量编译**

```bash
mvn -q -pl ruoyi-system -am compile && mvn -q -pl ruoyi-admin -am compile
```
Expected: BUILD SUCCESS。

- [ ] **Step 2: 后端测试(含新增项目测试 + 相关会话回归)**

```bash
mvn test -pl ruoyi-system -am -Dtest=AiProjectServiceTest -Dsurefire.failIfNoSpecifiedTests=false
```
Expected: 通过。

- [ ] **Step 3: 手动验收(本地起前后端)**

1. 登录桌面端 → 侧栏「项目」显示空态。
2. 点 + 新建项目「测试项目」→ 出现在项目区,带 0 会话。
3. 选中「测试项目」→ 新建对话 → 发一条消息 → 会话出现在该项目下。
4. 会话列表 `listSession` 带 projectId 过滤,只显示该项目会话。
5. 不选项目 → 新建对话 → 普通会话(未分组)。
6. 删除「测试项目」→ 弹确认 → 确认后项目及其下会话全部消失。
7. 已有会话(存量、无 projectId)→ 打开正常,未被误改归属。
8. job 会话等其它创建路径不受影响(发一次定时任务会话验证)。

- [ ] **Step 4: 更新文档**

在 `docs/整体架构总览.md` §8 数据落盘点速查表加一行 `ai_project`;在 `docs/聊天对话模块.md`(或会话相关文档)补一句 project_id 归属说明。

- [ ] **Step 5: 最终提交**

```bash
git add docs/
git commit -m "docs: 补充项目管理相关文档说明"
```

---

## 自审记录

- **Spec 覆盖**:ai_project 表(Task 1)、project_id 列(Task 1/4)、CRUD(Task 3/5)、级联删(Task 3)、桌面侧栏项目区(Task 8)、新建对话归属(Task 8)、过滤会话(Task 8)、仅本人数据(Task 5 归属校验)。
- **排除项**:无会话跨项目移动、无停用状态、无后台菜单、无项目级统计 —— 与 spec 一致。
- **类型一致性**:`ChatRunCreateCommand` 加 `projectId`(Long)后,REST controller / WS handler / ChatRunService 三处构造签名同步更新;`AiProject.projectId`/`projectName`/`description` 命名在 Domain/Mapper/Service/Controller/前端 api 中一致。
