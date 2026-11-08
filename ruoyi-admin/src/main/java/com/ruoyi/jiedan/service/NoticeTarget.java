package com.ruoyi.jiedan.service;

/** 同一条业务通知在微信、浏览器和备用推送渠道打开同一条记录。 */
public final class NoticeTarget
{
    final String path;
    final String label;

    private NoticeTarget(String route, String label)
    {
        this.path = "/#/" + route;
        this.label = label;
    }

    public static NoticeTarget order(Long id)
    {
        return id == null ? new NoticeTarget("workbench", "打开工作台")
                : new NoticeTarget("order?id=" + id, "打开项目");
    }

    public static NoticeTarget bug(Long orderId, Long bugId)
    {
        return new NoticeTarget("order?id=" + orderId + "&tab=bugs&bug=" + bugId, "查看问题");
    }

    public static NoticeTarget pendingBugs(Long id)
    {
        return new NoticeTarget("order?id=" + id + "&tab=bugs&todo=1", "处理项目问题");
    }

    public static NoticeTarget handling(Long id)
    {
        return new NoticeTarget("order?id=" + id + "&followup=manual", "处理项目");
    }

    public static NoticeTarget timeline(Long orderId, Long entryId, String type)
    {
        boolean chat = "message".equals(type) || "reply".equals(type);
        return new NoticeTarget("order?id=" + orderId + "&tab=" + (chat ? "chat" : "internal")
                + "&entry=" + entryId, chat ? "查看沟通" : "查看记录");
    }

    public static NoticeTarget quoteChat(Long id)
    {
        return new NoticeTarget("quotes?id=" + id + "&tab=chat", "回复报价反馈");
    }

    public static NoticeTarget quote(Long id)
    {
        return new NoticeTarget("quotes?id=" + id, "打开报价单");
    }
}
