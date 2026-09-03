package com.ruoyi.system.service.impl;

import com.ruoyi.system.domain.AiUpstreamModel;
import com.ruoyi.system.mapper.AiUpstreamModelMapper;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.Date;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class AiUpstreamModelMapperTest
{
    private SqlSession session;
    private AiUpstreamModelMapper mapper;

    @BeforeEach
    void setUp() throws Exception
    {
        var ds = org.h2.jdbcx.JdbcConnectionPool.create("jdbc:h2:mem:upstreamtest;MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        var config = new Configuration(new Environment("test", new JdbcTransactionFactory(), ds));
        try (InputStream in = getClass().getResourceAsStream("/mapper/system/AiUpstreamModelMapper.xml"))
        {
            assertNotNull(in);
            new XMLMapperBuilder(in, config, "mapper/system/AiUpstreamModelMapper.xml", config.getSqlFragments()).parse();
        }
        session = new SqlSessionFactoryBuilder().build(config).openSession();
        mapper = session.getMapper(AiUpstreamModelMapper.class);
        mapper.createTableForTest();
    }

    @AfterEach
    void tearDown()
    {
        if (mapper != null) mapper.dropTableForTest();
        if (session != null) { session.commit(); session.close(); }
    }

    @Test
    void insertBatch_thenSelectByChannel()
    {
        mapper.insertBatch(List.of(model(1L, "gpt-4o"), model(1L, "gpt-4o-mini"), model(2L, "claude-sonnet-4")));
        session.commit();
        AiUpstreamModel q = new AiUpstreamModel(); q.setChannelId(1L);
        List<AiUpstreamModel> rows = mapper.selectList(q);
        assertEquals(2, rows.size());
        assertEquals("gpt-4o", rows.get(0).getUpstreamModelId());
        assertEquals("GPT-gpt-4o", rows.get(0).getDisplayName());
    }

    @Test
    void deleteByChannelId_onlyAffectsThatChannel()
    {
        mapper.insertBatch(List.of(model(1L, "a"), model(2L, "b"))); session.commit();
        assertEquals(1, mapper.deleteByChannelId(1L)); session.commit();
        assertNull(mapper.selectByChannelAndModelId(1L, "a"));
        assertNotNull(mapper.selectByChannelAndModelId(2L, "b"));
    }

    @Test
    void uniqueKey_rejectsDuplicateWithinChannel()
    {
        mapper.insertOne(model(1L, "gpt-4o")); session.commit();
        assertThrows(Exception.class, () -> { mapper.insertOne(model(1L, "gpt-4o")); session.commit(); });
    }

    @Test
    void selectChannelIdsByUpstreamModelId_returnsOnlyMatchingChannels()
    {
        mapper.insertBatch(List.of(model(1L, "gpt-4o"), model(1L, "gpt-4o-mini"), model(2L, "gpt-4o")));
        session.commit();
        List<Long> ids = mapper.selectChannelIdsByUpstreamModelId("gpt-4o");
        assertEquals(2, ids.size());
        assertTrue(ids.containsAll(List.of(1L, 2L)));
    }

    private static AiUpstreamModel model(Long channelId, String id)
    {
        AiUpstreamModel m = new AiUpstreamModel();
        m.setChannelId(channelId); m.setUpstreamModelId(id); m.setDisplayName("GPT-" + id);
        m.setOwnedBy("test"); m.setSource(AiUpstreamModel.SOURCE_SYNC); m.setCreateBy("tester"); m.setCreateTime(new Date());
        return m;
    }
}
