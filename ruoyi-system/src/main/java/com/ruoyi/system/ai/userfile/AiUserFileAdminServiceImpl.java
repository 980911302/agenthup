package com.ruoyi.system.ai.userfile;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.ruoyi.ai.contract.storage.ObjectStorage;
import com.ruoyi.system.domain.AiUserFile;
import com.ruoyi.system.mapper.AiUserFileMapper;

/**
 * 个人文件管理端服务实现。
 *
 * @author ruoyi
 */
@Service
public class AiUserFileAdminServiceImpl implements IAiUserFileAdminService
{
    private static final Logger log = LoggerFactory.getLogger(AiUserFileAdminServiceImpl.class);

    private final AiUserFileMapper userFileMapper;
    private final AiUserFileProperties properties;
    private final ObjectProvider<ObjectStorage> storageProvider;

    public AiUserFileAdminServiceImpl(AiUserFileMapper userFileMapper,
                                      AiUserFileProperties properties,
                                      ObjectProvider<ObjectStorage> storageProvider)
    {
        this.userFileMapper = userFileMapper;
        this.properties = properties;
        this.storageProvider = storageProvider;
    }

    @Override
    public List<AiUserFile> selectAiUserFileList(AiUserFile query)
    {
        return userFileMapper.selectAdminList(query == null ? new AiUserFile() : query);
    }

    /**
     * 先软删台账、再清理无引用的对象。
     *
     * <p>顺序与 C 端删除一致:元数据先落,存储侧失败只记 warn 不回滚 —— 用户可见的删除
     * 已经生效,留下的孤儿对象不可达,可容忍。反过来先删对象则可能出现「台账还在但正文没了」。
     *
     * <p>秒传让多条记录可能共用一个 object_key,所以必须逐个复查引用计数,
     * 不能拿到 key 就删。
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public int deleteAiUserFileByIds(Long[] fileIds)
    {
        if (fileIds == null || fileIds.length == 0)
        {
            return 0;
        }
        List<AiUserFile> rows = userFileMapper.selectAdminByIds(fileIds);
        if (rows.isEmpty())
        {
            return 0;
        }
        int deleted = userFileMapper.softDeleteByIds(fileIds);

        ObjectStorage storage = storageProvider.getIfAvailable();
        if (storage == null)
        {
            log.warn("对象存储未配置,已软删 {} 条台账但未清理存储对象", deleted);
            return deleted;
        }
        List<String> orphans = new ArrayList<>();
        for (AiUserFile row : rows)
        {
            if (userFileMapper.countLiveByObjectKey(row.getObjectKey()) == 0)
            {
                orphans.add(row.getObjectKey());
            }
        }
        for (String key : orphans)
        {
            try
            {
                storage.delete(key);
            }
            catch (RuntimeException e)
            {
                log.warn("管理端删除对象失败,已留下孤儿对象: objectKey={} err={}", key, e.toString());
            }
        }
        return deleted;
    }

    @Override
    public List<Map<String, Object>> usageByUser()
    {
        return userFileMapper.selectAdminUsageByUser();
    }

    @Override
    public Map<String, Object> totals()
    {
        Map<String, Object> row = userFileMapper.selectAdminTotals();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("fileCount", row == null ? 0 : row.getOrDefault("fileCount", 0));
        out.put("usedBytes", row == null ? 0 : row.getOrDefault("usedBytes", 0));
        out.put("userCount", row == null ? 0 : row.getOrDefault("userCount", 0));
        // 配额当前是全局配置而非按用户存,这里回传是为了让管理端能算出「某人用了配额的百分之多少」
        out.put("userQuotaBytes", properties.getUserQuotaBytes());
        out.put("maxFileBytes", properties.getMaxFileBytes());
        out.put("maxFilesPerUser", properties.getMaxFilesPerUser());
        out.put("storageEnabled", storageProvider.getIfAvailable() != null);
        return out;
    }
}
