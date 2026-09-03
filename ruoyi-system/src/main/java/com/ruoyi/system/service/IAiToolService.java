package com.ruoyi.system.service;

import java.util.List;
import com.ruoyi.system.domain.AiTool;

public interface IAiToolService
{
    public AiTool selectAiToolById(Long toolId);
    public List<AiTool> selectAiToolList(AiTool aiTool);
    public int insertAiTool(AiTool aiTool);
    public int updateAiTool(AiTool aiTool);
    public int deleteAiToolById(Long toolId);
    public int deleteAiToolByIds(Long[] toolIds);
}
