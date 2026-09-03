package com.ruoyi.system.ai.userfile;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import com.ruoyi.ai.contract.storage.ObjectReadHandle;
import com.ruoyi.ai.contract.storage.ObjectStorage;
import com.ruoyi.ai.contract.storage.ObjectWriteRequest;
import com.ruoyi.ai.contract.storage.StoredObject;
import com.ruoyi.system.domain.AiUserFile;
import com.ruoyi.system.mapper.AiUserFileMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 管理端服务:跨用户可见性、批量删除与对象清理、秒传共用对象时的引用计数。
 */
class AiUserFileAdminServiceTest
{
    private InMemoryMapper mapper;
    private InMemoryStorage storage;
    private AiUserFileAdminServiceImpl admin;

    @BeforeEach
    void setUp()
    {
        mapper = new InMemoryMapper();
        storage = new InMemoryStorage();
        AiUserFileProperties props = new AiUserFileProperties();
        props.setUserQuotaBytes(1000);
        admin = new AiUserFileAdminServiceImpl(mapper, props, provider(storage));
    }

    @Test
    void adminList_seesAllUsers()
    {
        seed(1L, "a.txt", "k1", 10);
        seed(2L, "b.txt", "k2", 20);

        // C 端查询强制带 userId，管理端反过来必须能跨用户看
        assertEquals(2, admin.selectAiUserFileList(new AiUserFile()).size());
    }

    @Test
    void adminList_filtersByUser()
    {
        seed(1L, "a.txt", "k1", 10);
        seed(2L, "b.txt", "k2", 20);

        AiUserFile q = new AiUserFile();
        q.setUserId(2L);
        List<AiUserFile> rows = admin.selectAiUserFileList(q);
        assertEquals(1, rows.size());
        assertEquals("b.txt", rows.get(0).getFileName());
    }

    @Test
    void deleteByIds_softDeletesAndRemovesObjects()
    {
        AiUserFile a = seed(1L, "a.txt", "k1", 10);
        AiUserFile b = seed(2L, "b.txt", "k2", 20);
        storage.objects.put("k1", new byte[10]);
        storage.objects.put("k2", new byte[20]);

        assertEquals(2, admin.deleteAiUserFileByIds(new Long[]{a.getFileId(), b.getFileId()}));
        assertEquals(0, admin.selectAiUserFileList(new AiUserFile()).size());
        assertTrue(storage.objects.isEmpty(), "无引用的对象应被清理");
    }

    @Test
    void deleteByIds_keepsObjectStillReferencedBySecondRow()
    {
        // 秒传：两条记录共用一个 object_key
        AiUserFile a = seed(1L, "a.txt", "shared", 10);
        seed(1L, "copy.txt", "shared", 10);
        storage.objects.put("shared", new byte[10]);

        admin.deleteAiUserFileByIds(new Long[]{a.getFileId()});

        assertTrue(storage.objects.containsKey("shared"),
                "还有存活记录引用时不能删对象，否则另一条会读到空");
    }

    @Test
    void deleteByIds_emptyInput_isNoop()
    {
        assertEquals(0, admin.deleteAiUserFileByIds(null));
        assertEquals(0, admin.deleteAiUserFileByIds(new Long[0]));
    }

    @Test
    void totals_carriesQuotaConfigForPercentage()
    {
        seed(1L, "a.txt", "k1", 10);
        Map<String, Object> t = admin.totals();

        assertEquals(1, t.get("fileCount"));
        // 配额是全局配置而非按用户存，管理端靠它算使用率
        assertEquals(1000L, t.get("userQuotaBytes"));
        assertEquals(Boolean.TRUE, t.get("storageEnabled"));
    }

    @Test
    void totals_withoutStorage_reportsDisabled()
    {
        AiUserFileAdminServiceImpl noStorage = new AiUserFileAdminServiceImpl(
                mapper, new AiUserFileProperties(), provider(null));
        assertEquals(Boolean.FALSE, noStorage.totals().get("storageEnabled"));
    }

    @Test
    void deleteByIds_withoutStorage_stillSoftDeletes()
    {
        AiUserFile a = seed(1L, "a.txt", "k1", 10);
        AiUserFileAdminServiceImpl noStorage = new AiUserFileAdminServiceImpl(
                mapper, new AiUserFileProperties(), provider(null));

        // 存储未配置不该挡住台账清理，否则管理员连记录都删不掉
        assertEquals(1, noStorage.deleteAiUserFileByIds(new Long[]{a.getFileId()}));
        assertEquals(0, noStorage.selectAiUserFileList(new AiUserFile()).size());
    }

    private AiUserFile seed(Long userId, String name, String key, long size)
    {
        AiUserFile row = new AiUserFile();
        row.setUserId(userId);
        row.setFileName(name);
        row.setObjectKey(key);
        row.setFileSize(size);
        row.setCreateTime(new Date());
        mapper.insertAiUserFile(row);
        return row;
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

    private static final class InMemoryStorage implements ObjectStorage
    {
        private final Map<String, byte[]> objects = new LinkedHashMap<>();
        @Override public StoredObject put(ObjectWriteRequest r) { return null; }
        @Override public ObjectReadHandle open(String key) { return null; }
        @Override public StoredObject stat(String key) { return null; }
        @Override public void delete(String key) { objects.remove(key); }
        @Override public String presignedUrl(String k, Duration t, String f) { return ""; }
    }

    /** 只实现管理端用到的方法；C 端方法由 AiUserFileServiceTest 覆盖。 */
    private static final class InMemoryMapper implements AiUserFileMapper
    {
        private final Map<Long, AiUserFile> rows = new LinkedHashMap<>();
        private final AtomicLong seq = new AtomicLong(100);

        private List<AiUserFile> live()
        {
            List<AiUserFile> out = new ArrayList<>();
            for (AiUserFile r : rows.values()) if ("0".equals(r.getDelFlag())) out.add(r);
            return out;
        }

        @Override public List<AiUserFile> selectAdminList(AiUserFile q)
        {
            List<AiUserFile> out = new ArrayList<>();
            for (AiUserFile r : live())
            {
                if (q.getUserId() != null && !q.getUserId().equals(r.getUserId())) continue;
                if (q.getFileName() != null && !r.getFileName().contains(q.getFileName())) continue;
                out.add(r);
            }
            return out;
        }

        @Override public List<AiUserFile> selectAdminByIds(Long[] fileIds)
        {
            List<AiUserFile> out = new ArrayList<>();
            for (Long id : fileIds)
            {
                AiUserFile r = rows.get(id);
                if (r != null && "0".equals(r.getDelFlag())) out.add(r);
            }
            return out;
        }

        @Override public int softDeleteByIds(Long[] fileIds)
        {
            int n = 0;
            for (Long id : fileIds)
            {
                AiUserFile r = rows.get(id);
                if (r != null && "0".equals(r.getDelFlag())) { r.setDelFlag("2"); n++; }
            }
            return n;
        }

        @Override public int countLiveByObjectKey(String objectKey)
        {
            int n = 0;
            for (AiUserFile r : live()) if (r.getObjectKey().equals(objectKey)) n++;
            return n;
        }

        @Override public int insertAiUserFile(AiUserFile f)
        {
            f.setFileId(seq.incrementAndGet());
            f.setDelFlag("0");
            rows.put(f.getFileId(), f);
            return 1;
        }

        @Override public Map<String, Object> selectAdminTotals()
        {
            long bytes = 0;
            java.util.Set<Long> users = new java.util.LinkedHashSet<>();
            for (AiUserFile r : live()) { bytes += r.getFileSize(); users.add(r.getUserId()); }
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("fileCount", live().size());
            m.put("usedBytes", bytes);
            m.put("userCount", users.size());
            return m;
        }

        @Override public List<Map<String, Object>> selectAdminUsageByUser() { return List.of(); }

        /* 以下为 C 端方法，本测试不使用 */
        @Override public List<AiUserFile> selectByUser(AiUserFile q) { return List.of(); }
        @Override public AiUserFile selectByIdAndUser(Long a, Long b) { return null; }
        @Override public AiUserFile selectByUserAndHash(Long a, String b) { return null; }
        @Override public Long sumSizeByUser(Long userId) { return 0L; }
        @Override public int countByUser(Long userId) { return 0; }
        @Override public int updateAiUserFile(AiUserFile f) { return 0; }
        @Override public int softDeleteByIdAndUser(Long a, Long b) { return 0; }
    }
}
