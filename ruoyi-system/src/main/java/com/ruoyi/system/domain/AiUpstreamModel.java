package com.ruoyi.system.domain;

import com.ruoyi.common.core.domain.BaseEntity;
import org.apache.commons.lang3.builder.ToStringBuilder;
import org.apache.commons.lang3.builder.ToStringStyle;

/**
 * 上游模型(渠道可用模型清单)。
 *
 * <p>这张表是候选池而不是已启用模型。导入时从这里挑选模型,再建立模型定义和渠道供应绑定。
 *
 * @author ruoyi
 */
public class AiUpstreamModel extends BaseEntity
{
    private static final long serialVersionUID = 1L;

    public static final String SOURCE_MANUAL = "0";
    public static final String SOURCE_SYNC = "1";

    private Long id;
    private Long channelId;
    private String upstreamModelId;
    private String displayName;
    private String ownedBy;
    private String source;

    /** 上游声明的输入模态,逗号分隔(image/file/video/audio);空/null 表示上游未提供 */
    private String inputModalities;
    /** 瞬时字段:渠道名,列表联表展示用,不落库 */
    private String channelName;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getChannelId() { return channelId; }
    public void setChannelId(Long channelId) { this.channelId = channelId; }
    public String getUpstreamModelId() { return upstreamModelId; }
    public void setUpstreamModelId(String upstreamModelId) { this.upstreamModelId = upstreamModelId; }
    public String getDisplayName() { return displayName; }
    public void setDisplayName(String displayName) { this.displayName = displayName; }
    public String getOwnedBy() { return ownedBy; }
    public void setOwnedBy(String ownedBy) { this.ownedBy = ownedBy; }
    public String getSource() { return source; }
    public void setSource(String source) { this.source = source; }
    public String getChannelName() { return channelName; }
    public void setChannelName(String channelName) { this.channelName = channelName; }

    @Override
    public String toString()
    {
        return new ToStringBuilder(this, ToStringStyle.MULTI_LINE_STYLE)
            .append("id", getId())
            .append("channelId", getChannelId())
            .append("upstreamModelId", getUpstreamModelId())
            .append("displayName", getDisplayName())
            .append("ownedBy", getOwnedBy())
            .append("source", getSource())
            .append("createBy", getCreateBy())
            .append("createTime", getCreateTime())
            .toString();
    }

    public String getInputModalities()
    {
        return inputModalities;
    }

    public void setInputModalities(String inputModalities)
    {
        this.inputModalities = inputModalities;
    }

}
