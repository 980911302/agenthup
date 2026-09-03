package com.ruoyi.system.ai.memory.longterm;

import com.ruoyi.system.mapper.AiMemoryMapper;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;

import javax.sql.DataSource;
import java.io.InputStream;

/**
 * H2(mem、MySQL 模式)+ 原生 MyBatis 的记忆测试基座,不启动 Spring 上下文。
 *
 * <p>建表走 {@code AiMemoryMapper.xml} 的 createTableForTest;向量检索用内存实现
 * {@link InMemoryMemoryVectorStore}(pgvector 不在 H2 内,维度路由等 SQL 行为单测覆盖不到,
 * 由真库集成测试补)。装配用反射注入 private 字段(同 {@code ChatMessageMapperTestSupport})。
 *
 * @author ruoyi
 */
public abstract class MemoryTestSupport
{
    public SqlSession session;
    public AiMemoryMapper mapper;
    public MemoryServiceImpl service;
    public InMemoryMemoryVectorStore vectorStore;

    @BeforeEach
    protected void setUpBase() throws Exception
    {
        String dbName = "memtest_" + getClass().getSimpleName();
        DataSource ds = org.h2.jdbcx.JdbcConnectionPool.create(
                "jdbc:h2:mem:" + dbName + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        Environment env = new Environment("test", new JdbcTransactionFactory(), ds);
        Configuration config = new Configuration(env);
        try (InputStream in = getClass().getResourceAsStream("/mapper/system/AiMemoryMapper.xml"))
        {
            assert in != null : "mapper xml 未找到";
            XMLMapperBuilder builder = new XMLMapperBuilder(in, config,
                    "mapper/system/AiMemoryMapper.xml", config.getSqlFragments());
            builder.parse();
        }
        SqlSessionFactory factory = new SqlSessionFactoryBuilder().build(config);

        session = factory.openSession();
        mapper = session.getMapper(AiMemoryMapper.class);
        mapper.createTableForTest();

        vectorStore = new InMemoryMemoryVectorStore();
        MemoryServiceImpl svc = new MemoryServiceImpl();
        setField(svc, "memoryMapper", mapper);
        setField(svc, "vectorStore", vectorStore);
        service = svc;
    }

    @AfterEach
    protected void tearDownBase()
    {
        if (mapper != null)
        {
            session.commit();
        }
        if (session != null)
        {
            session.close();
        }
    }

    /** 反射注入 private 字段;沿继承链向上找 */
    public static void setField(Object target, String name, Object value) throws Exception
    {
        Class<?> c = target.getClass();
        while (c != null)
        {
            try
            {
                java.lang.reflect.Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                f.set(target, value);
                return;
            }
            catch (NoSuchFieldException e)
            {
                c = c.getSuperclass();
            }
        }
        throw new NoSuchFieldException(name);
    }
}
