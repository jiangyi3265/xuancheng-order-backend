package com.ruoyi.jiedan.mapper;

import java.util.List;
import org.apache.ibatis.annotations.Param;
import com.ruoyi.jiedan.domain.JiedanBugUpdate;

public interface JiedanBugUpdateMapper
{
    List<JiedanBugUpdate> selectByBugId(Long bugId);

    List<JiedanBugUpdate> selectByOrderId(Long orderId);

    int insert(JiedanBugUpdate update);

    int insertMeta(@Param("updateId") Long updateId, @Param("bugId") Long bugId,
                   @Param("senderKind") String senderKind, @Param("senderId") Long senderId,
                   @Param("key") String key, @Param("hash") String hash);
    String customerReplyHash(@Param("bugId") Long bugId, @Param("customerId") Long customerId, @Param("key") String key);
    boolean isTeamReply(@Param("bugId") Long bugId, @Param("updateId") Long updateId);
    int markSeen(@Param("customerId") Long customerId, @Param("bugId") Long bugId, @Param("updateId") Long updateId);

    int updateAttachments(@org.apache.ibatis.annotations.Param("id") Long id,
                          @org.apache.ibatis.annotations.Param("attachments") String attachments);

    int deleteByBugId(Long bugId);

    int deleteByOrderId(Long orderId);
}
