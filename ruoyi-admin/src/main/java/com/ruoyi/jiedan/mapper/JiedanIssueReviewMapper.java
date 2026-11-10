package com.ruoyi.jiedan.mapper;

import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Param;
import com.ruoyi.jiedan.domain.JiedanOrder;

public interface JiedanIssueReviewMapper
{
    JiedanOrder lockProject(@Param("bugId") Long bugId, @Param("ownerId") Long ownerId, @Param("account") String account);
    Map<String, Object> latest(Long bugId);
    List<Map<String, Object>> latestByProject(Long orderId);
    String requestHash(@Param("bugId") Long bugId, @Param("senderId") Long senderId, @Param("key") String key);
    void insert(Map<String, Object> row);
    int decide(@Param("id") Long id, @Param("state") String state, @Param("hash") String hash);
    int cancelForProject(Long orderId);
    int cancelForCustomer(Long customerId);
}
