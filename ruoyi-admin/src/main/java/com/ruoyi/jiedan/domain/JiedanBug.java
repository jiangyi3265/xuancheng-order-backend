package com.ruoyi.jiedan.domain;

import java.util.Date;

/**
 * 项目 Bug 清单，独立于沟通和内部记录。
 */
public class JiedanBug
{
    private Long id;
    private Long orderId;
    private String content;
    /** 附件（JSON 字符串） */
    private String attachments;
    /** 状态：open 未解决，resolved 已解决 */
    private String status;
    private String createdBy;
    private Date createTime;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getOrderId() { return orderId; }
    public void setOrderId(Long orderId) { this.orderId = orderId; }
    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }
    public String getAttachments() { return attachments; }
    public void setAttachments(String attachments) { this.attachments = attachments; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getCreatedBy() { return createdBy; }
    public void setCreatedBy(String createdBy) { this.createdBy = createdBy; }
    public Date getCreateTime() { return createTime; }
    public void setCreateTime(Date createTime) { this.createTime = createTime; }
}
