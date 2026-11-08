package com.ruoyi.jiedan.mapper;

import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Param;

public interface JiedanQuoteMapper {
    List<Map<String,Object>> list(@Param("employeeId") Long employeeId, @Param("memberId") Long memberId);
    Map<String,Object> find(@Param("id") Long id, @Param("lock") boolean lock, @Param("memberId") Long memberId);
    int insert(Map<String,Object> row);
    List<Map<String,Object>> counters();
    void ensureCounter(@Param("source") String source);
    Long lockCounter(@Param("source") String source);
    int advanceCounter(@Param("source") String source, @Param("number") long number);
    boolean numberExists(@Param("source") String source, @Param("number") long number);
    int setInitialTitle(@Param("id") Long id, @Param("title") String title);
    int update(Map<String,Object> row);
    int rename(@Param("id") Long id, @Param("title") String title);
    int markRead(@Param("memberId") Long memberId, @Param("employeeId") Long employeeId,
                 @Param("items") List<Map<String,Long>> items);
    List<Map<String,Object>> conversation(@Param("quoteId") Long quoteId, @Param("employee") Long employee,
                                        @Param("before") Long before);
    int insertMessage(Map<String,Object> message);
    Long findClientKey(@Param("quoteId") Long quoteId, @Param("authorId") Long authorId, @Param("clientKey") String clientKey);
    int touchConversation(@Param("id") Long id);
    int markRepliesRead(@Param("member") Long member, @Param("items") List<Map<String,Long>> items);
    int activeCount(Long employeeId);
}
