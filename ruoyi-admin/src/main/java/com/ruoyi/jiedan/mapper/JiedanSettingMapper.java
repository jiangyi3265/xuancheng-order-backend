package com.ruoyi.jiedan.mapper;

import java.util.List;
import org.apache.ibatis.annotations.Param;
import com.ruoyi.jiedan.domain.JiedanSetting;

public interface JiedanSettingMapper
{
    String selectValue(String key);

    List<JiedanSetting> selectAll();

    int upsert(@Param("key") String key, @Param("value") String value);
}
