package com.ruoyi.system.ai.userfile;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mock.web.MockMultipartFile;

import com.ruoyi.ai.contract.storage.ObjectReadHandle;
import com.ruoyi.ai.contract.storage.ObjectStorage;
import com.ruoyi.ai.contract.storage.ObjectWriteRequest;
import com.ruoyi.ai.contract.storage.StoredObject;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.system.domain.AiUserFile;
import com.ruoyi.system.mapper.AiChatSessionMapper;
import com.ruoyi.system.mapper.AiProjectMapper;
import com.ruoyi.system.mapper.AiUserFileMapper;
import com.ruoyi.system.tool.AiToolProperties;
import com.ruoyi.system.tool.WorkspaceScopeService;

import static org.mockito.Mockito.mock;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 个人文件服务:配额、秒传、删除时的对象引用计数、文件名净化。
 *
 * <p>用手写的内存桩而不是 Mockito:这几条规则的正确性依赖「mapper 与 storage 的状态
 * 真的随操作变化」,用 when/thenReturn 摆出来的固定返回值验证不了删除后计数归零这类时序。
 */
class AiUserFileServiceTest
{
    private InMemoryMapper mapper;
    private InMemoryStorage storage;
    private AiUserFileProperties properties;
    private AiUserFileServiceImpl service;

    private static final Long USER = 42L;

    @BeforeEach
    void setUp()
    {
        mapper = new InMemoryMapper();
        storage = new InMemoryStorage();
        properties = new AiUserFileProperties();
        properties.setUserQuotaBytes(1000);
        properties.setMaxFileBytes(500);
        properties.setMaxFilesPerUser(3);
        service = new AiUserFileServiceImpl(mapper, properties, new AiToolProperties(),
                new WorkspaceScopeService(), provider(storage));
    }

    @Test
    void upload_persistsMetadataAndObject()
    {
        AiUserFile saved = service.upload(USER, "zhang", file("report.csv", "a,b,c"));

        assertNotNull(saved.getFileId());
        assertEquals("report.csv", saved.getFileName());
        assertEquals(USER, saved.getUserId());
        assertTrue(storage.objects.containsKey(saved.getObjectKey()), "对象应已写入存储");
        // 对象键不含原始文件名,只保留扩展名
        assertTrue(saved.getObjectKey().startsWith("user/42/"), saved.getObjectKey());
        assertTrue(saved.getObjectKey().endsWith(".csv"), saved.getObjectKey());
        assertTrue(!saved.getObjectKey().contains("report"), "对象键不该带原始文件名");
    }

    @Test
    void upload_overQuota_rejected()
    {
        service.upload(USER, "zhang", file("big.bin", "x".repeat(400)));
        service.upload(USER, "zhang", file("big2.bin", "y".repeat(400)));

        // 已用 800,配额 1000,再传 400 应被拒
        ServiceException e = assertThrows(ServiceException.class,
                () -> service.upload(USER, "zhang", file("big3.bin", "z".repeat(400))));
        assertTrue(e.getMessage().contains("存储空间不足"), e.getMessage());
    }

    @Test
    void upload_overSingleFileLimit_rejected()
    {
        ServiceException e = assertThrows(ServiceException.class,
                () -> service.upload(USER, "zhang", file("huge.bin", "x".repeat(501))));
        assertTrue(e.getMessage().contains("上限"), e.getMessage());
    }

    @Test
    void upload_overFileCountLimit_rejected()
    {
        for (int i = 0; i < 3; i++)
        {
            service.upload(USER, "zhang", file("f" + i + ".txt", "content-" + i));
        }
        ServiceException e = assertThrows(ServiceException.class,
                () -> service.upload(USER, "zhang", file("f4.txt", "content-4")));
        assertTrue(e.getMessage().contains("文件数已达上限"), e.getMessage());
    }

    @Test
    void upload_sameContentTwice_reusesObject()
    {
        AiUserFile first = service.upload(USER, "zhang", file("a.txt", "same-content"));
        AiUserFile second = service.upload(USER, "zhang", file("b.txt", "same-content"));

        assertEquals(first.getObjectKey(), second.getObjectKey(), "相同内容应复用对象");
        assertEquals(1, storage.objects.size(), "存储里只该有一份");
        assertEquals(1, storage.putCount, "第二次不该再写存储");
        // 展示名各自独立
        assertEquals("a.txt", first.getFileName());
        assertEquals("b.txt", second.getFileName());
    }

    @Test
    void upload_differentUsersSameContent_doNotShareObject()
    {
        AiUserFile mine = service.upload(USER, "zhang", file("a.txt", "shared"));
        AiUserFile theirs = service.upload(99L, "li", file("a.txt", "shared"));

        // 跨用户不复用:否则「我删了文件对方还看得见」需要全局引用计数才解释得清
        assertTrue(!mine.getObjectKey().equals(theirs.getObjectKey()));
        assertEquals(2, storage.objects.size());
    }

    @Test
    void delete_lastReference_removesObject()
    {
        AiUserFile row = service.upload(USER, "zhang", file("a.txt", "only-one"));

        service.delete(USER, row.getFileId());

        assertNull(service.get(USER, row.getFileId()), "元数据应已软删");
        assertTrue(storage.objects.isEmpty(), "最后一条引用被删,对象也该删掉");
    }

    @Test
    void delete_whileOtherReferenceAlive_keepsObject()
    {
        AiUserFile first = service.upload(USER, "zhang", file("a.txt", "dup"));
        AiUserFile second = service.upload(USER, "zhang", file("b.txt", "dup"));

        service.delete(USER, first.getFileId());

        assertNull(service.get(USER, first.getFileId()));
        assertNotNull(service.get(USER, second.getFileId()));
        assertTrue(storage.objects.containsKey(second.getObjectKey()),
                "还有存活引用时不能删对象,否则另一条记录会读到空");
    }

    @Test
    void delete_otherUsersFile_rejected()
    {
        AiUserFile row = service.upload(USER, "zhang", file("a.txt", "mine"));

        assertThrows(ServiceException.class, () -> service.delete(99L, row.getFileId()));
        assertNotNull(service.get(USER, row.getFileId()), "越权删除不能生效");
    }

    @Test
    void get_otherUsersFile_returnsNull()
    {
        AiUserFile row = service.upload(USER, "zhang", file("a.txt", "mine"));
        assertNull(service.get(99L, row.getFileId()));
    }

    @Test
    void upload_traversalFileName_sanitized()
    {
        AiUserFile row = service.upload(USER, "zhang",
                file("../../etc/passwd", "root:x:0:0"));
        // 目录成分被剥掉,只留基本名 —— 这个名字后面会当作 attachToSession 的落盘文件名
        assertEquals("passwd", row.getFileName());
    }

    @Test
    void upload_dotOnlyFileName_replaced()
    {
        assertEquals("unnamed", service.upload(USER, "z", file("..", "x")).getFileName());
    }

    @Test
    void quota_reflectsLiveRowsOnly()
    {
        AiUserFile a = service.upload(USER, "zhang", file("a.txt", "12345"));
        service.upload(USER, "zhang", file("b.txt", "678901234"));
        assertEquals(14, service.quota(USER).usedBytes());

        service.delete(USER, a.getFileId());
        assertEquals(9, service.quota(USER).usedBytes(), "软删的行不该再计入已用量");
        assertEquals(1000 - 9, service.quota(USER).remainingBytes());
    }

    @Test
    void rename_keepsObjectKey()
    {
        AiUserFile row = service.upload(USER, "zhang", file("old.txt", "body"));
        String key = row.getObjectKey();

        AiUserFile renamed = service.rename(USER, row.getFileId(), "  new.txt  ");

        assertEquals("new.txt", renamed.getFileName());
        assertEquals(key, service.get(USER, row.getFileId()).getObjectKey(), "重命名不该动对象键");
    }

    @Test
    void storageUnavailable_uploadGivesFriendlyMessage()
    {
        AiUserFileServiceImpl noStorage = new AiUserFileServiceImpl(mapper, properties,
                new AiToolProperties(), new WorkspaceScopeService(), provider(null));

        assertTrue(!noStorage.storageAvailable());
        ServiceException e = assertThrows(ServiceException.class,
                () -> noStorage.upload(USER, "zhang", file("a.txt", "x")));
        assertTrue(e.getMessage().contains("对象存储未配置"), e.getMessage());
    }

    @Test
    void saveFromWorkspace_persistsFileIntoPersonalSpace(@TempDir Path tempDir) throws Exception
    {
        AiUserFileServiceImpl svc = serviceWithWorkspace(tempDir);
        Path ws = tempDir.resolve("sess-1");
        Files.createDirectories(ws);
        Files.writeString(ws.resolve("report.md"), "# 产出\n正文");

        AiUserFile saved = svc.saveFromWorkspace(USER, "zhang", "sess-1", "report.md", null);

        assertEquals("report.md", saved.getFileName());
        assertTrue(storage.objects.containsKey(saved.getObjectKey()));
        assertEquals(1, svc.quota(USER).fileCount());
    }

    @Test
    void saveFromWorkspace_pathTraversal_rejected(@TempDir Path tempDir) throws Exception
    {
        AiUserFileServiceImpl svc = serviceWithWorkspace(tempDir);
        Files.createDirectories(tempDir.resolve("sess-1"));
        // 沙箱外的文件：即便真实存在也不能被「保存到个人文件」捞出来
        Files.writeString(tempDir.resolve("outside.txt"), "secret");

        assertThrows(Exception.class,
                () -> svc.saveFromWorkspace(USER, "zhang", "sess-1", "../outside.txt", null));
        assertTrue(storage.objects.isEmpty(), "越界路径不该产生任何对象");
    }

    @Test
    void saveFromWorkspace_missingFile_rejected(@TempDir Path tempDir) throws Exception
    {
        AiUserFileServiceImpl svc = serviceWithWorkspace(tempDir);
        Files.createDirectories(tempDir.resolve("sess-1"));

        ServiceException e = assertThrows(ServiceException.class,
                () -> svc.saveFromWorkspace(USER, "zhang", "sess-1", "nope.txt", null));
        assertTrue(e.getMessage().contains("文件不存在"), e.getMessage());
    }

    @Test
    void saveFromWorkspace_sameContentAsUpload_reusesObject(@TempDir Path tempDir) throws Exception
    {
        AiUserFileServiceImpl svc = serviceWithWorkspace(tempDir);
        Path ws = tempDir.resolve("sess-1");
        Files.createDirectories(ws);
        Files.writeString(ws.resolve("dup.txt"), "same-bytes");

        svc.upload(USER, "zhang", file("a.txt", "same-bytes"));
        AiUserFile fromWs = svc.saveFromWorkspace(USER, "zhang", "sess-1", "dup.txt", null);

        // 秒传逻辑对两个入口一视同仁：内容相同就只存一份
        assertEquals(1, storage.objects.size());
        assertEquals(1, storage.putCount);
        assertEquals("dup.txt", fromWs.getFileName());
    }

    @Test
    void saveFromWorkspace_overQuota_rejected(@TempDir Path tempDir) throws Exception
    {
        AiUserFileServiceImpl svc = serviceWithWorkspace(tempDir);
        Path ws = tempDir.resolve("sess-1");
        Files.createDirectories(ws);
        Files.writeString(ws.resolve("big.bin"), "z".repeat(600));

        // 配额校验对两个入口同样生效（maxFileBytes=500）
        assertThrows(ServiceException.class,
                () -> svc.saveFromWorkspace(USER, "zhang", "sess-1", "big.bin", null));
    }

    /**
     * 把工作区根指到临时目录，避免测试碰到真实的 ./agent-java/ai/workspace。
     *
     * <p>WorkspaceScopeService 必须给 mapper：它的无参构造会把 sessionMapper 留成 null，
     * 而 resolveWorkspaceKey 直接用它，一调就 NPE。mock 默认返回 null 正好走「普通会话」分支。
     */
    private AiUserFileServiceImpl serviceWithWorkspace(Path root)
    {
        AiToolProperties props = new AiToolProperties();
        props.setWorkspaceRoot(root.toString());
        props.setWorkspacePerSession(true);
        AiChatSessionMapper sessionMapper = mock(AiChatSessionMapper.class);
        return new AiUserFileServiceImpl(mapper, properties, props,
                new WorkspaceScopeService(sessionMapper, mock(AiProjectMapper.class)), provider(storage));
    }

    @Test
    void listQuery_usesSortMode_notOrderBy()
    {
        service.upload(USER, "zhang", file("a.txt", "x"));
        service.list(USER, null, "name");

        assertEquals("name", mapper.lastQuery.getSortMode());
    }

    /**
     * 回归:实体上不能有名为 orderBy 的属性。
     *
     * <p>PageHelper 开了 supportMethodsArguments,会反射读取参数对象的 orderBy 并拼成
     * {@code order by ${orderBy}},绕过 Mapper XML 的 choose 白名单 —— 线上表现是
     * 「Unknown column 'date' in 'order clause'」的 500,而且等于把用户可控字符串
     * 送进 SQL 拼接路径。这条测试钉住字段名,防止有人改回去。
     */
    @Test
    void entity_hasNoOrderByProperty_pageHelperWouldHijackIt()
    {
        for (java.lang.reflect.Field f : AiUserFile.class.getDeclaredFields())
        {
            assertTrue(!"orderBy".equals(f.getName()),
                    "AiUserFile 不能有 orderBy 字段,会被 PageHelper 劫持");
        }
        for (java.lang.reflect.Method m : AiUserFile.class.getMethods())
        {
            assertTrue(!"getOrderBy".equals(m.getName()),
                    "AiUserFile 不能有 getOrderBy(),会被 PageHelper 劫持");
        }
    }

    /* ---------------- 桩 ---------------- */

    private static MockMultipartFile file(String name, String content)
    {
        return new MockMultipartFile("file", name, "text/plain",
                content.getBytes(StandardCharsets.UTF_8));
    }

    private static ObjectProvider<ObjectStorage> provider(ObjectStorage storage)
    {
        return new ObjectProvider<>()
        {
            @Override public ObjectStorage getObject() { return storage; }
            @Override public ObjectStorage getObject(Object... args) { return storage; }
            @Override public ObjectStorage getIfAvailable() { return storage; }
            @Override public ObjectStorage getIfUnique() { return storage; }
        };
    }

    /** 内存版对象存储。 */
    private static final class InMemoryStorage implements ObjectStorage
    {
        private final Map<String, byte[]> objects = new LinkedHashMap<>();
        private int putCount;

        @Override public StoredObject put(ObjectWriteRequest request)
        {
            try
            {
                objects.put(request.key(), request.content().readAllBytes());
                putCount++;
                return new StoredObject(request.key(), request.size(), request.contentType(),
                        "etag", Instant.now());
            }
            catch (Exception e)
            {
                throw new IllegalStateException(e);
            }
        }

        @Override public ObjectReadHandle open(String key)
        {
            byte[] bytes = objects.get(key);
            if (bytes == null) return null;
            StoredObject object = new StoredObject(key, bytes.length, "text/plain", "etag", Instant.now());
            InputStream in = new ByteArrayInputStream(bytes);
            return new ObjectReadHandle()
            {
                @Override public StoredObject object() { return object; }
                @Override public InputStream stream() { return in; }
                @Override public void close() { }
            };
        }

        @Override public StoredObject stat(String key)
        {
            byte[] bytes = objects.get(key);
            return bytes == null ? null
                    : new StoredObject(key, bytes.length, "text/plain", "etag", Instant.now());
        }

        @Override public void delete(String key) { objects.remove(key); }

        @Override public String presignedUrl(String key, Duration ttl, String downloadFilename)
        {
            return "https://stub/" + key;
        }
    }

    /** 内存版 mapper,复刻 XML 里的 del_flag / user_id 过滤语义。 */
    private static final class InMemoryMapper implements AiUserFileMapper
    {
        private final Map<Long, AiUserFile> rows = new LinkedHashMap<>();
        private final AtomicLong sequence = new AtomicLong(100);
        private AiUserFile lastQuery;

        private List<AiUserFile> live(Long userId)
        {
            List<AiUserFile> out = new ArrayList<>();
            for (AiUserFile row : rows.values())
            {
                if ("0".equals(row.getDelFlag()) && row.getUserId().equals(userId)) out.add(row);
            }
            return out;
        }

        @Override public List<AiUserFile> selectByUser(AiUserFile query)
        {
            lastQuery = query;
            List<AiUserFile> out = new ArrayList<>();
            for (AiUserFile row : live(query.getUserId()))
            {
                if (query.getKeyword() == null || row.getFileName().contains(query.getKeyword()))
                {
                    out.add(row);
                }
            }
            return out;
        }

        @Override public AiUserFile selectByIdAndUser(Long fileId, Long userId)
        {
            AiUserFile row = rows.get(fileId);
            return row != null && "0".equals(row.getDelFlag()) && row.getUserId().equals(userId)
                    ? row : null;
        }

        @Override public AiUserFile selectByUserAndHash(Long userId, String contentHash)
        {
            for (AiUserFile row : live(userId))
            {
                if (contentHash != null && contentHash.equals(row.getContentHash())) return row;
            }
            return null;
        }

        @Override public Long sumSizeByUser(Long userId)
        {
            long total = 0;
            for (AiUserFile row : live(userId)) total += row.getFileSize();
            return total;
        }

        @Override public int countByUser(Long userId) { return live(userId).size(); }

        @Override public int countLiveByObjectKey(String objectKey)
        {
            int count = 0;
            for (AiUserFile row : rows.values())
            {
                if ("0".equals(row.getDelFlag()) && row.getObjectKey().equals(objectKey)) count++;
            }
            return count;
        }

        @Override public int insertAiUserFile(AiUserFile file)
        {
            file.setFileId(sequence.incrementAndGet());
            file.setDelFlag("0");
            rows.put(file.getFileId(), file);
            return 1;
        }

        @Override public int updateAiUserFile(AiUserFile file)
        {
            AiUserFile row = rows.get(file.getFileId());
            if (row == null || !row.getUserId().equals(file.getUserId())) return 0;
            if (file.getFileName() != null) row.setFileName(file.getFileName());
            if (file.getFileSize() != null) row.setFileSize(file.getFileSize());
            if (file.getContentType() != null) row.setContentType(file.getContentType());
            return 1;
        }

        /* 管理端方法：本测试只覆盖 C 端，这里给空实现即可 */
        @Override public List<AiUserFile> selectAdminList(AiUserFile query) { return List.of(); }
        @Override public List<AiUserFile> selectAdminByIds(Long[] fileIds) { return List.of(); }
        @Override public int softDeleteByIds(Long[] fileIds) { return 0; }
        @Override public List<Map<String, Object>> selectAdminUsageByUser() { return List.of(); }
        @Override public Map<String, Object> selectAdminTotals() { return Map.of(); }

        @Override public int softDeleteByIdAndUser(Long fileId, Long userId)
        {
            AiUserFile row = rows.get(fileId);
            if (row == null || !row.getUserId().equals(userId) || !"0".equals(row.getDelFlag()))
            {
                return 0;
            }
            row.setDelFlag("2");
            return 1;
        }
    }
}
