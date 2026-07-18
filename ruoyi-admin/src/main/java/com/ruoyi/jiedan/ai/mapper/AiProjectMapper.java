package com.ruoyi.jiedan.ai.mapper;

import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Param;
import com.ruoyi.jiedan.ai.domain.AiProject;

public interface AiProjectMapper
{
    List<Map<String, Object>> selectList(@Param("keyword") String keyword, @Param("enabled") Integer enabled);
    AiProject selectById(Long id);
    AiProject selectByIdForUpdate(Long id);
    int insert(AiProject project);
    int update(AiProject project);
}
