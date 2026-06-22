package com.ruoyi.jiedan.domain;

import java.math.BigDecimal;
import java.util.Date;

/**
 * 接单订单 jiedan_order
 */
public class JiedanOrder
{
    private Long id;
    private String orderNo;
    private String title;
    private String channel;
    private String customer;
    private String contact;
    private BigDecimal amount;
    private Long ownerId;
    private String status;
    private String priority;
    private String deadline;
    private String requirement;
    /** 需求材料（JSON 字符串） */
    private String attachments;
    /** 未读成员 id，逗号分隔 */
    private String unread;
    private Integer paid;
    private Integer revisions;
    private Date createTime;
    private Date updateTime;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getOrderNo() { return orderNo; }
    public void setOrderNo(String orderNo) { this.orderNo = orderNo; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getChannel() { return channel; }
    public void setChannel(String channel) { this.channel = channel; }
    public String getCustomer() { return customer; }
    public void setCustomer(String customer) { this.customer = customer; }
    public String getContact() { return contact; }
    public void setContact(String contact) { this.contact = contact; }
    public BigDecimal getAmount() { return amount; }
    public void setAmount(BigDecimal amount) { this.amount = amount; }
    public Long getOwnerId() { return ownerId; }
    public void setOwnerId(Long ownerId) { this.ownerId = ownerId; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getPriority() { return priority; }
    public void setPriority(String priority) { this.priority = priority; }
    public String getDeadline() { return deadline; }
    public void setDeadline(String deadline) { this.deadline = deadline; }
    public String getRequirement() { return requirement; }
    public void setRequirement(String requirement) { this.requirement = requirement; }
    public String getAttachments() { return attachments; }
    public void setAttachments(String attachments) { this.attachments = attachments; }
    public String getUnread() { return unread; }
    public void setUnread(String unread) { this.unread = unread; }
    public Integer getPaid() { return paid; }
    public void setPaid(Integer paid) { this.paid = paid; }
    public Integer getRevisions() { return revisions; }
    public void setRevisions(Integer revisions) { this.revisions = revisions; }
    public Date getCreateTime() { return createTime; }
    public void setCreateTime(Date createTime) { this.createTime = createTime; }
    public Date getUpdateTime() { return updateTime; }
    public void setUpdateTime(Date updateTime) { this.updateTime = updateTime; }
}
