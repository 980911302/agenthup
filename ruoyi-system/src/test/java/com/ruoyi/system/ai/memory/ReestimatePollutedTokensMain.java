package com.ruoyi.system.ai.memory;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/**
 * 一次性工具：重估历史虚高的 {@code ai_chat_message.tokens}（Task C 方案①）。
 *
 * <p>限定：{@code visible_to_llm='0' AND message_type='ASSISTANT'
 * AND tokens > char_length(content)} —— 与取证 SQL 一致，禁止全表更新。
 *
 * <pre>
 * mvn -q -pl ruoyi-system -DskipTests test-compile exec:java \
 *   -Dexec.classpathScope=test \
 *   -Dexec.mainClass=com.ruoyi.system.ai.memory.ReestimatePollutedTokensMain
 * </pre>
 *
 * <p>可通过系统属性覆盖库连接（默认本机 application-druid.yml）：
 * {@code -Ddb.url=... -Ddb.user=... -Ddb.password=...}
 */
public final class ReestimatePollutedTokensMain
{
    private static final String SELECT_SQL = """
            select message_id, tokens, content
              from ai_chat_message
             where visible_to_llm = '0'
               and message_type = 'ASSISTANT'
               and tokens > char_length(ifnull(content, ''))
            """;

    private static final String UPDATE_SQL = """
            update ai_chat_message
               set tokens = ?, usage_source = '1'
             where message_id = ?
               and tokens > char_length(ifnull(content, ''))
            """;

    private ReestimatePollutedTokensMain()
    {
    }

    public static void main(String[] args) throws Exception
    {
        String url = System.getProperty("db.url",
                "jdbc:mysql://127.0.0.1:3306/agent-java?useUnicode=true&characterEncoding=utf8&serverTimezone=GMT%2B8");
        String user = System.getProperty("db.user", "root");
        String password = System.getProperty("db.password", "root123");

        TokenEstimator estimator = new TokenEstimator();

        try (Connection conn = DriverManager.getConnection(url, user, password))
        {
            conn.setAutoCommit(false);

            List<Row> rows = new ArrayList<>();
            try (Statement st = conn.createStatement();
                 ResultSet rs = st.executeQuery(SELECT_SQL))
            {
                while (rs.next())
                {
                    rows.add(new Row(
                            rs.getLong("message_id"),
                            rs.getInt("tokens"),
                            rs.getString("content")));
                }
            }

            System.out.println("受影响行数: " + rows.size());
            if (rows.isEmpty())
            {
                System.out.println("无需回填。");
                return;
            }

            int updated = 0;
            try (PreparedStatement ps = conn.prepareStatement(UPDATE_SQL))
            {
                for (Row row : rows)
                {
                    int estimated = estimator.estimate(row.content);
                    // 空内容至少写 0；有内容时至少 1，避免全 0 让环图丢段
                    if (row.content != null && !row.content.isEmpty() && estimated <= 0)
                    {
                        estimated = 1;
                    }
                    ps.setInt(1, Math.max(estimated, 0));
                    ps.setLong(2, row.messageId);
                    updated += ps.executeUpdate();
                    System.out.printf("message_id=%d  tokens %d -> %d  (chars=%d)%n",
                            row.messageId, row.oldTokens, estimated,
                            row.content == null ? 0 : row.content.length());
                }
            }

            // 验收：极端虚高应消失。
            // 注意：cl100k 对中文本身就可能 tokens > char_length(content)，
            // 不能再用「tokens > chars」当失败条件；改用 tokens > 3 * chars 抓真污染。
            int extreme;
            try (Statement st = conn.createStatement();
                 ResultSet rs = st.executeQuery("""
                         select count(1) from ai_chat_message
                          where visible_to_llm = '0'
                            and message_type = 'ASSISTANT'
                            and char_length(ifnull(content, '')) > 0
                            and tokens > 3 * char_length(content)
                         """))
            {
                rs.next();
                extreme = rs.getInt(1);
            }

            if (extreme > 0)
            {
                conn.rollback();
                System.err.println("验收失败：仍有 tokens > 3*chars 的极端行 " + extreme + " 条，已回滚。");
                System.exit(2);
            }
            if (updated != rows.size())
            {
                conn.rollback();
                System.err.println("验收失败：更新行数 " + updated + " != 选中 " + rows.size() + "，已回滚。");
                System.exit(2);
            }

            conn.commit();
            System.out.println("已提交。更新行数=" + updated + "，极端虚高(tokens>3*chars)=0");
        }
    }

    private record Row(long messageId, int oldTokens, String content)
    {
    }
}
