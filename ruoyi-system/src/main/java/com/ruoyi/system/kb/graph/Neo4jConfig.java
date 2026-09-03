package com.ruoyi.system.kb.graph;

import org.neo4j.driver.AuthTokens;
import org.neo4j.driver.Driver;
import org.neo4j.driver.GraphDatabase;
import org.neo4j.driver.Session;
import org.neo4j.driver.SessionConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import jakarta.annotation.PreDestroy;

/**
 * Neo4j 原生 Driver(线程安全连接池,单例即可)。
 * <p>{@code ai.kb.neo4j.enabled=true} 时装配;启动时幂等建约束与索引。
 */
@Configuration
@ConditionalOnProperty(name = "ai.kb.neo4j.enabled", havingValue = "true")
public class Neo4jConfig
{
    private static final Logger log = LoggerFactory.getLogger(Neo4jConfig.class);

    private Driver driver;

    @Bean(destroyMethod = "close")
    public Driver neo4jDriver(
            @Value("${ai.kb.neo4j.uri:bolt://localhost:7687}") String uri,
            @Value("${ai.kb.neo4j.username}") String username,
            @Value("${ai.kb.neo4j.password}") String password,
            @Value("${ai.kb.neo4j.database:neo4j}") String database)
    {
        log.info("初始化 Neo4j Driver uri={} database={}", uri, database);
        driver = GraphDatabase.driver(uri, AuthTokens.basic(username, password));
        ensureSchema(driver, database);
        return driver;
    }

    private static void ensureSchema(Driver driver, String database)
    {
        SessionConfig cfg = SessionConfig.builder().withDatabase(database).build();
        try (Session session = driver.session(cfg))
        {
            // 稳定身份唯一键 (kbId, entityKey)；name 仅展示，允许同名异义
            // 旧约束 (kbId,name) 与 entityKey 设计冲突，启动时幂等迁移
            try
            {
                session.run("DROP CONSTRAINT entity_kb_name IF EXISTS").consume();
            }
            catch (Exception dropEx)
            {
                log.debug("删除旧 entity_kb_name 约束: {}", dropEx.getMessage());
            }
            // 补全历史节点 entityKey（name 唯一时代的数据）
            try
            {
                session.run("""
                    MATCH (e:Entity)
                    WHERE e.entityKey IS NULL AND e.kbId IS NOT NULL AND e.name IS NOT NULL
                    SET e.entityKey = toString(e.kbId) + '|' + e.name + '||'
                    """).consume();
            }
            catch (Exception backfillEx)
            {
                log.warn("回填 entityKey 失败: {}", backfillEx.getMessage());
            }
            session.run(
                "CREATE CONSTRAINT entity_kb_entity_key IF NOT EXISTS "
                    + "FOR (e:Entity) REQUIRE (e.kbId, e.entityKey) IS UNIQUE");
            session.run(
                "CREATE INDEX entity_kb IF NOT EXISTS FOR (e:Entity) ON (e.kbId)");
            session.run(
                "CREATE INDEX entity_kb_name_idx IF NOT EXISTS FOR (e:Entity) ON (e.kbId, e.name)");
            // 全文索引供关键词匹配实体名
            session.run(
                "CREATE FULLTEXT INDEX entity_name_ft IF NOT EXISTS "
                    + "FOR (e:Entity) ON EACH [e.name]");
            log.info("Neo4j 约束与索引已确保存在 (entityKey 唯一)");
        }
        catch (Exception e)
        {
            log.error("Neo4j 建约束/索引失败(后续图操作可能失败): {}", e.getMessage());
            throw e;
        }
    }

    @PreDestroy
    public void destroy()
    {
        if (driver != null)
        {
            driver.close();
        }
    }
}
