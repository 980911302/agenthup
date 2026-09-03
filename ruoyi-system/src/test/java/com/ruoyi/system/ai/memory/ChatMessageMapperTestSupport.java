package com.ruoyi.system.ai.memory;

import com.ruoyi.system.mapper.AiChatMessageMapper;
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
 * H2(mem、MySQL 模式)+ 原生 MyBatis 的测试基座,不启动 Spring 上下文。
 *
 * <p>mapper XML 走 classpath 加载(就是 src/main 那份,含 createTableForTest 的 H2 建表语句),
 * 因此表结构改动漏改 XML 会在这里直接暴露。装配用反射注入 private 字段(@Autowired 在无 Spring 时无效)。
 *
 * <p>抽出来是因为工具往返重建、压缩、清理回写都需要同一套建表 + 装配,复制三份必然漂移。
 *
 * @author ruoyi
 */
public abstract class ChatMessageMapperTestSupport
{
    // public 而非 protected:ContextCompactorTest 在 ai.context 包下继承本类,
    // 跨包继承时 protected 字段可访问但 IDE/编译期噪音大,统一 public 省事
    public SqlSession session;
    public AiChatMessageMapper mapper;
    public ChatMessageRecorder recorder;
    public DbChatMemory chatMemory;
    public TokenEstimator tokenEstimator;

    // 必须 protected/public:package-private 方法不被其他包的子类继承,
    // ContextCompactorTest 在 ai.context 包下,写成包级可见 JUnit 根本不会调它 —— 表现为 NPE
    @BeforeEach
    protected void setUpBase() throws Exception
    {
        // 每个测试类用独立库名,避免并行执行时互相看到对方的表
        String dbName = "aitest_" + getClass().getSimpleName();
        DataSource ds = org.h2.jdbcx.JdbcConnectionPool.create(
                "jdbc:h2:mem:" + dbName + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        Environment env = new Environment("test", new JdbcTransactionFactory(), ds);
        Configuration config = new Configuration(env);
        try (InputStream in = getClass().getResourceAsStream("/mapper/system/AiChatMessageMapper.xml"))
        {
            assert in != null : "mapper xml 未找到";
            XMLMapperBuilder builder = new XMLMapperBuilder(in, config,
                    "mapper/system/AiChatMessageMapper.xml", config.getSqlFragments());
            builder.parse();
        }
        SqlSessionFactory factory = new SqlSessionFactoryBuilder().build(config);

        session = factory.openSession();
        mapper = session.getMapper(AiChatMessageMapper.class);
        mapper.createTableForTest();

        tokenEstimator = new TokenEstimator();
        ChatMessageRecorder rec = new ChatMessageRecorder();
        setField(rec, "mapper", mapper);
        setField(rec, "contextFileStore", null);
        setField(rec, "tokenEstimator", tokenEstimator);
        setField(rec, "inlineLimit", 2048);
        recorder = rec;

        DbChatMemory mem = new DbChatMemory();
        setField(mem, "recorder", recorder);
        setField(mem, "tokenEstimator", tokenEstimator);
        setField(mem, "maxResultChars", 30000);
        chatMemory = mem;
    }

    @AfterEach
    protected void tearDownBase()
    {
        if (mapper != null)
        {
            mapper.dropTableForTest();
        }
        if (session != null)
        {
            session.commit();
            session.close();
        }
    }

    /** 反射注入 private 字段;沿继承链向上找,子类基类字段都能设 */
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
