package com.ruoyi.system.mapper;

import java.util.List;
import org.apache.ibatis.annotations.Param;
import com.ruoyi.system.domain.KbAclMember;

/**
 * 知识库成员 ACL
 */
public interface KbAclMemberMapper
{
    List<KbAclMember> selectByKbId(Long kbId);

    KbAclMember selectByKbAndUser(@Param("kbId") Long kbId, @Param("userId") Long userId);

    int insert(KbAclMember row);

    /**
     * 幂等写入角色：存在则更新 role，不存在则插入。
     * 负责人转移时旧 owner 降为 EDITOR 必须走此方法，避免唯一键冲突。
     */
    int upsert(KbAclMember row);

    int updateRole(KbAclMember row);

    int deleteByKbAndUser(@Param("kbId") Long kbId, @Param("userId") Long userId);

    int deleteByKbId(Long kbId);
}
