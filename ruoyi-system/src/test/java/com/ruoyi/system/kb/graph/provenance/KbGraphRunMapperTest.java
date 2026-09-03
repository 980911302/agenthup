package com.ruoyi.system.kb.graph.provenance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.sql.Connection;
import java.sql.Statement;
import java.util.Date;
import java.util.List;
import javax.sql.DataSource;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import com.ruoyi.system.domain.KbDocGraph;
import com.ruoyi.system.domain.KbGraphRun;
import com.ruoyi.system.mapper.KbDocGraphMapper;
import com.ruoyi.system.mapper.KbGraphRunMapper;

/**
 * kb_graph_run / kb_doc_graph v2 列 round-trip（H2 PostgreSQL 模式，不启 Spring）。
 */
class KbGraphRunMapperTest
{
    private DataSource dataSource;
    private SqlSession session;
    private KbGraphRunMapper runMapper;
    private KbDocGraphMapper docGraphMapper;

    @BeforeEach
    void setUp() throws Exception
    {
        dataSource = org.h2.jdbcx.JdbcConnectionPool.create(
            "jdbc:h2:mem:kb_graph_v2_" + System.nanoTime()
                + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1",
            "sa", "");
        try (Connection c = dataSource.getConnection(); Statement st = c.createStatement())
        {
            st.execute("""
                create table if not exists kb_doc_graph (
                  doc_id bigint primary key,
                  kb_id bigint not null,
                  graph_status varchar(20) not null default 'PENDING',
                  graph_step varchar(40),
                  progress int default 0,
                  chunk_total int default 0,
                  chunk_done int default 0,
                  entity_count int default 0,
                  relation_count int default 0,
                  extract_model varchar(100),
                  error_type varchar(50),
                  error_msg varchar(2000),
                  started_at timestamp,
                  finished_at timestamp,
                  active_run_id bigint,
                  generation bigint default 0,
                  graph_version varchar(64)
                )
                """);
            st.execute("""
                create table if not exists kb_graph_run (
                  run_id bigint primary key,
                  kb_id bigint not null,
                  doc_id bigint not null,
                  generation bigint not null,
                  source_content_hash varchar(64),
                  parser_version varchar(20),
                  chunk_params_hash varchar(64),
                  extractor_version varchar(40),
                  prompt_version varchar(40),
                  model_code varchar(100),
                  status varchar(20) not null default 'PENDING',
                  step varchar(40),
                  error_type varchar(50),
                  error_msg varchar(2000),
                  entity_count int default 0,
                  relation_count int default 0,
                  evidence_count int default 0,
                  extract_outcome varchar(30),
                  started_at timestamp,
                  finished_at timestamp,
                  create_time timestamp default current_timestamp
                )
                """);
            // selectByKbId 左联文档表
            st.execute("""
                create table if not exists kb_document (
                  doc_id bigint primary key,
                  doc_name varchar(200),
                  del_flag char(1) default '0'
                )
                """);
        }

        Environment env = new Environment("test", new JdbcTransactionFactory(), dataSource);
        Configuration config = new Configuration(env);
        config.getTypeAliasRegistry().registerAlias("KbGraphRun", KbGraphRun.class);
        config.getTypeAliasRegistry().registerAlias("KbDocGraph", KbDocGraph.class);
        loadMapper(config, "/mapper/system/KbGraphRunMapper.xml");
        loadMapper(config, "/mapper/system/KbDocGraphMapper.xml");
        SqlSessionFactory factory = new SqlSessionFactoryBuilder().build(config);
        session = factory.openSession(true);
        runMapper = session.getMapper(KbGraphRunMapper.class);
        docGraphMapper = session.getMapper(KbDocGraphMapper.class);
    }

    @AfterEach
    void tearDown()
    {
        if (session != null)
        {
            session.close();
        }
    }

    @Test
    void runInsertSelectUpdateAndDeleteRoundTrip()
    {
        KbGraphRun run = sampleRun(1001L, 10L, 20L, 1L);
        assertEquals(1, runMapper.insert(run));

        KbGraphRun loaded = runMapper.selectByRunId(1001L);
        assertNotNull(loaded);
        assertEquals(10L, loaded.getKbId());
        assertEquals(20L, loaded.getDocId());
        assertEquals(1L, loaded.getGeneration());
        assertEquals(GraphRunStatus.RUNNING, loaded.getStatus());
        assertEquals("3", loaded.getParserVersion());
        assertEquals(GraphExtractOutcome.SUCCESS, loaded.getExtractOutcome());

        loaded.setStatus(GraphRunStatus.SUCCESS);
        loaded.setEntityCount(5);
        loaded.setRelationCount(3);
        loaded.setEvidenceCount(7);
        loaded.setFinishedAt(new Date());
        assertEquals(1, runMapper.update(loaded));

        KbGraphRun latest = runMapper.selectLatestByDocId(20L);
        assertEquals(1001L, latest.getRunId());
        assertEquals(GraphRunStatus.SUCCESS, latest.getStatus());
        assertEquals(5, latest.getEntityCount());

        // 更高 generation
        KbGraphRun run2 = sampleRun(1002L, 10L, 20L, 2L);
        runMapper.insert(run2);
        List<KbGraphRun> all = runMapper.selectByDocId(20L);
        assertEquals(2, all.size());
        assertEquals(2L, all.get(0).getGeneration());

        assertEquals(2, runMapper.deleteByDocId(20L));
        assertNull(runMapper.selectByRunId(1001L));
    }

    @Test
    void docGraphSelectAndUpdatePersistActiveRunAndGeneration() throws Exception
    {
        // H2 对 ON CONFLICT 支持不完整；生产 upsert 面向 PostgreSQL。
        // 此处用 JDBC 插入后走 mapper update/select 验证 v2 列。
        insertDocGraphRaw(30L, 10L, "READY", 2001L, 4L, "pv3|chunk|ex1");

        KbDocGraph loaded = docGraphMapper.selectByDocId(30L);
        assertNotNull(loaded);
        assertEquals(2001L, loaded.getActiveRunId());
        assertEquals(4L, loaded.getGeneration());
        assertEquals("pv3|chunk|ex1", loaded.getGraphVersion());

        loaded.setGeneration(5L);
        loaded.setActiveRunId(2002L);
        loaded.setGraphStatus("READY");
        loaded.setProgress(100);
        assertEquals(1, docGraphMapper.updateProgress(loaded));
        KbDocGraph again = docGraphMapper.selectByDocId(30L);
        assertEquals(5L, again.getGeneration());
        assertEquals(2002L, again.getActiveRunId());
    }

    @Test
    void generationSupportAlignsWithStoredValues() throws Exception
    {
        insertDocGraphRaw(40L, 10L, "PENDING", null, 0L, null);

        KbDocGraph g = docGraphMapper.selectByDocId(40L);
        long next = GraphGenerationSupport.nextGeneration(g.getGeneration());
        KbGraphRun run = sampleRun(3001L, 10L, 40L, next);
        runMapper.insert(run);

        g.setActiveRunId(run.getRunId());
        g.setGeneration(next);
        g.setGraphStatus("RUNNING");
        docGraphMapper.updateProgress(g);

        KbDocGraph active = docGraphMapper.selectByDocId(40L);
        assertTrue(GraphGenerationSupport.canCommit(
            active.getActiveRunId(), active.getGeneration(), run.getRunId(), run.getGeneration()));
        assertTrue(GraphGenerationSupport.isStaleGeneration(active.getGeneration(), next));
        assertFalse(GraphGenerationSupport.isStaleGeneration(active.getGeneration(), next + 1));
    }

    private void insertDocGraphRaw(long docId, long kbId, String status, Long activeRunId,
        long generation, String graphVersion) throws Exception
    {
        try (Connection c = dataSource.getConnection();
             var ps = c.prepareStatement(
                 "insert into kb_doc_graph(doc_id, kb_id, graph_status, active_run_id, generation, graph_version) "
                     + "values (?,?,?,?,?,?)"))
        {
            ps.setLong(1, docId);
            ps.setLong(2, kbId);
            ps.setString(3, status);
            if (activeRunId == null)
            {
                ps.setObject(4, null);
            }
            else
            {
                ps.setLong(4, activeRunId);
            }
            ps.setLong(5, generation);
            ps.setString(6, graphVersion);
            ps.executeUpdate();
        }
    }

    private static KbGraphRun sampleRun(long runId, long kbId, long docId, long generation)
    {
        KbGraphRun run = new KbGraphRun();
        run.setRunId(runId);
        run.setKbId(kbId);
        run.setDocId(docId);
        run.setGeneration(generation);
        run.setSourceContentHash("abc");
        run.setParserVersion("3");
        run.setChunkParamsHash("chunk-fp");
        run.setExtractorVersion(GraphProvenanceModel.PROVENANCE_VERSION);
        run.setPromptVersion("p1");
        run.setModelCode("cheap-model");
        run.setStatus(GraphRunStatus.RUNNING);
        run.setStep("extract");
        run.setEntityCount(0);
        run.setRelationCount(0);
        run.setEvidenceCount(0);
        run.setExtractOutcome(GraphExtractOutcome.SUCCESS);
        run.setStartedAt(new Date());
        return run;
    }

    private static void loadMapper(Configuration config, String path) throws Exception
    {
        try (InputStream in = KbGraphRunMapperTest.class.getResourceAsStream(path))
        {
            assert in != null : "mapper missing: " + path;
            XMLMapperBuilder builder = new XMLMapperBuilder(in, config, path, config.getSqlFragments());
            builder.parse();
        }
    }
}
