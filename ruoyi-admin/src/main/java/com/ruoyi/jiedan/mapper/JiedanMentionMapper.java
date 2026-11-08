package com.ruoyi.jiedan.mapper;

import java.util.Date;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Param;

/** 内部记录里的 @ 提醒：谁被 @、谁 @ 的、是否已读 */
public interface JiedanMentionMapper
{
    int insert(@Param("orderId") Long orderId, @Param("timelineId") Long timelineId, @Param("memberId") Long memberId,
               @Param("fromId") Long fromId, @Param("fromName") String fromName, @Param("content") String content,
               @Param("createTime") Date createTime);

    /** 某个项目的全部 @：timelineId, memberId, readTime */
    List<Map<String, Object>> selectByOrderId(@Param("orderId") Long orderId);

    /** 被 @ 的人打开项目：把他在这个项目里的未读 @ 全部标为已读，返回改了几条 */
    int markRead(@Param("orderId") Long orderId, @Param("memberId") Long memberId, @Param("readTime") Date readTime);

    /** 每个人还没看的 @ 条数（每日汇总用）：memberId, cnt */
    List<Map<String, Object>> countUnreadByMember();

    int deleteByOrderId(@Param("orderId") Long orderId);
}
