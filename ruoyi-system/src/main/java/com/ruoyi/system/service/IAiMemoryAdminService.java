package com.ruoyi.system.service;

import java.util.List;
import com.ruoyi.system.domain.AiMemory;

/**
 * 长期记忆管理服务。
 *
 * <p>仅供具备管理权限的后台接口调用；不同于对话读写链路，它可跨用户查看台账，但删除时
 * 必须同步删除 PostgreSQL 向量，避免记忆正文已隐藏、向量却仍可被检索的孤儿数据。
 */
public interface IAiMemoryAdminService
{
    List<AiMemory> selectAiMemoryList(AiMemory query);

    int deleteAiMemoryByIds(Long[] memoryIds);
}
