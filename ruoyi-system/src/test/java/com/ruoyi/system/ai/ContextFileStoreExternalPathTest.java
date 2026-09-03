package com.ruoyi.system.ai;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 外置大字段的存/取回路。
 *
 * <p>这条回路断掉的表现很隐蔽:读不到文件时上下文静默退化成表内 2KB 预览,
 * 而 tokens 列仍按全文估算 —— 模型少看了工具输出,刻度条还偏高,没有任何报错。
 * 所以路径格式与兼容性必须锁死。
 */
class ContextFileStoreExternalPathTest
{
    private static final String SESSION = "0123456789abcdef0123456789abcdef";

    /** 落库的必须是相对根目录的路径:换挂载点/换容器路径之后老数据才还找得到。 */
    @Test
    void savedPathIsRelativeToContextRoot(@TempDir Path root)
    {
        ContextFileStore store = new ContextFileStore(root.toString());

        String path = store.saveToolResult(SESSION, null, "工具全文");

        assertThat(Path.of(path).isAbsolute())
                .as("存绝对路径会把数据钉死在某台机器的某个目录上")
                .isFalse();
        assertThat(path).startsWith(SESSION);
        assertThat(store.loadExternal(path)).isEqualTo("工具全文");
    }

    /**
     * 可移植性的正题:存储整体搬家(换挂载点 / 换容器路径)之后,库里那条引用照样能读。
     * 存绝对路径时这一条必然失败 —— 这正是要存相对路径的理由。
     */
    @Test
    void storedReferenceSurvivesMovingTheWholeStore(@TempDir Path base) throws Exception
    {
        Path oldRoot = base.resolve("old-mount");
        String path = new ContextFileStore(oldRoot.toString())
                .saveToolResult(SESSION, null, "全文");

        Path newRoot = base.resolve("new-mount");
        Files.move(oldRoot, newRoot);

        ContextFileStore moved = new ContextFileStore(newRoot.toString());
        assertThat(moved.loadExternal(path)).isEqualTo("全文");
    }

    /**
     * 旧数据:context-path 曾是 ./ 开头,库里存的是「相对进程工作目录」的路径。
     * <p>用真实 CWD 相对化构造该形态 —— {@code user.dir} 系统属性改了也不影响
     * {@code toAbsolutePath()},只有拿真 CWD 才测得准。
     */
    @Test
    void legacyWorkingDirRelativePathStillResolves(@TempDir Path root) throws Exception
    {
        ContextFileStore store = new ContextFileStore(root.toString());
        Path file = root.resolve(SESSION).resolve("tools").resolve("legacy.txt");
        Files.createDirectories(file.getParent());
        Files.writeString(file, "旧数据全文", StandardCharsets.UTF_8);

        Path cwd = Path.of("").toAbsolutePath();
        String legacyValue = cwd.relativize(file).toString();

        assertThat(store.loadExternal(legacyValue)).isEqualTo("旧数据全文");
    }

    /** 更早的数据存的是绝对路径,根目录没变时照样读得出来。 */
    @Test
    void legacyAbsolutePathStillResolves(@TempDir Path root) throws Exception
    {
        ContextFileStore store = new ContextFileStore(root.toString());
        Path file = root.resolve(SESSION).resolve("tools").resolve("abs.txt");
        Files.createDirectories(file.getParent());
        Files.writeString(file, "绝对路径全文", StandardCharsets.UTF_8);

        assertThat(store.loadExternal(file.toString())).isEqualTo("绝对路径全文");
    }

    /** 越界路径一律拒绝:两个读入口收敛成一个之后,这道校验对上下文重建同样生效。 */
    @Test
    void pathOutsideContextRootIsRejected(@TempDir Path base) throws Exception
    {
        Path root = base.resolve("sessions");
        ContextFileStore store = new ContextFileStore(root.toString());
        Path outside = base.resolve("secret.txt");
        Files.writeString(outside, "不该被读到", StandardCharsets.UTF_8);

        assertThat(store.loadExternal(outside.toString())).isNull();
        assertThat(store.loadExternal("../secret.txt")).isNull();
    }

    /** 文件没了不能抛异常打断整轮对话,返回 null 交给调用方退化成表内预览。 */
    @Test
    void missingFileReturnsNullInsteadOfThrowing(@TempDir Path root)
    {
        ContextFileStore store = new ContextFileStore(root.toString());

        assertThat(store.loadExternal(SESSION + "/tools/nope.txt")).isNull();
        assertThat(store.loadExternal(null)).isNull();
        assertThat(store.loadExternal("  ")).isNull();
    }

    /** 思考溢出与工具结果共用同一套存取,子目录分开只为审计好认。 */
    @Test
    void thinkingOverflowUsesSameRoundTrip(@TempDir Path root)
    {
        ContextFileStore store = new ContextFileStore(root.toString());

        String path = store.saveThinking(SESSION, null, "推理链全文");

        assertThat(path).startsWith(SESSION).contains("thinking");
        assertThat(store.loadExternal(path)).isEqualTo("推理链全文");
    }
}
