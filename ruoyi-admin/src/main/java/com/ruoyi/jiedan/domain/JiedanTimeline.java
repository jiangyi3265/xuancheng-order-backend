package com.ruoyi.jiedan.domain;

import java.util.Date;

/**
 * 接单进度时间线 jiedan_timeline
 */
public class JiedanTimeline
{
    private Long id;
    private Long orderId;
    private String userName;
    private String type;
    private String content;
    /** 附件（JSON 字符串） */
    private String attachments;
    private Date createTime;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getOrderId() { return orderId; }
    public void setOrderId(Long orderId) { this.orderId = orderId; }
    public String getUserName() { return userName; }
    public void setUserName(String userName) { this.userName = userName; }
    public String getType() { return type; }
    public void setType(String type) { this.type = type; }
    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }
    public String getAttachments() { return attachments; }
    public void setAttachments(String attachments) { this.attachments = attachments; }
    public Date getCreateTime() { return createTime; }
    public void setCreateTime(Date createTime) { this.createTime = createTime; }
}
