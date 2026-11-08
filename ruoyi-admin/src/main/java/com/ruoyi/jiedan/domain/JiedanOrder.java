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
    /** 归属客户账号（sys_user.user_name），客户端凭此看自己的项目 */
    private String customerAccount;
    private String contact;
    private BigDecimal amount;
    private Long ownerId;
    private String status;
    private Boolean handled;
    private String handledTime;
    private String urgentUntil;
    private String priority;
    private String deadline;
    private String requirement;
    /** 需求材料（JSON 字符串） */
    private String attachments;
    /** 项目记事本正文（内部使用，客户端不可见） */
    private String notes;
    /** 记事本附件（JSON 字符串，图片/文档） */
    private String noteAttachments;
    /** 未读成员 id，逗号分隔 */
    private String unread;
    /** 是否已收清：由收款流水自动推算（已收 > 0 且 ≥ 报价），不接受前端直接设置 */
    private Integer paid;
    /** 已收金额 = 收款流水合计 */
    private BigDecimal receivedAmount;
    /** 接收状态：空=无需接收/已接收；pending=老板刚派过来，等负责人接收或拒绝 */
    private String assignState;
    private Integer revisions;
    /** 数据版本，每次项目内容变化自动递增，用于轻量实时同步 */
    private Long version;
    private Date createTime;
    private Date updateTime;
    /** 0 正常 1 已删除（回收站） */
    private Integer delFlag;
    private Date deleteTime;
    private String deleteBy;
    /** 删除人账号 id，客户自己删除时为空 */
    private Long deleteById;
    /** 下一步：做什么（内部字段，客户看不到） */
    private String nextStep;
    /** 下一步：谁来做 */
    private Long nextOwnerId;
    /** 下一步：截止日期 yyyy-MM-dd */
    private String nextDue;

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
    public String getCustomerAccount() { return customerAccount; }
    public void setCustomerAccount(String customerAccount) { this.customerAccount = customerAccount; }
    public String getContact() { return contact; }
    public void setContact(String contact) { this.contact = contact; }
    public BigDecimal getAmount() { return amount; }
    public void setAmount(BigDecimal amount) { this.amount = amount; }
    public Long getOwnerId() { return ownerId; }
    public void setOwnerId(Long ownerId) { this.ownerId = ownerId; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public Boolean getHandled() { return handled; }
    public void setHandled(Boolean handled) { this.handled = handled; }
    public String getUrgentUntil() { return urgentUntil; }
    public void setUrgentUntil(String urgentUntil) { this.urgentUntil = urgentUntil; }

    public String getHandledTime() { return handledTime; }
    public void setHandledTime(String handledTime) { this.handledTime = handledTime; }
    public String getPriority() { return priority; }
    public void setPriority(String priority) { this.priority = priority; }
    public String getDeadline() { return deadline; }
    public void setDeadline(String deadline) { this.deadline = deadline; }
    public String getRequirement() { return requirement; }
    public void setRequirement(String requirement) { this.requirement = requirement; }
    public String getAttachments() { return attachments; }
    public void setAttachments(String attachments) { this.attachments = attachments; }
    public String getNotes() { return notes; }
    public void setNotes(String notes) { this.notes = notes; }
    public String getNoteAttachments() { return noteAttachments; }
    public void setNoteAttachments(String noteAttachments) { this.noteAttachments = noteAttachments; }
    public String getUnread() { return unread; }
    public void setUnread(String unread) { this.unread = unread; }
    public Integer getPaid() { return paid; }
    public void setPaid(Integer paid) { this.paid = paid; }
    public BigDecimal getReceivedAmount() { return receivedAmount; }
    public void setReceivedAmount(BigDecimal receivedAmount) { this.receivedAmount = receivedAmount; }
    public String getAssignState() { return assignState; }
    public void setAssignState(String assignState) { this.assignState = assignState; }
    public Integer getRevisions() { return revisions; }
    public void setRevisions(Integer revisions) { this.revisions = revisions; }
    public Long getVersion() { return version; }
    public void setVersion(Long version) { this.version = version; }
    public Date getCreateTime() { return createTime; }
    public void setCreateTime(Date createTime) { this.createTime = createTime; }
    public Date getUpdateTime() { return updateTime; }
    public void setUpdateTime(Date updateTime) { this.updateTime = updateTime; }
    public Integer getDelFlag() { return delFlag; }
    public void setDelFlag(Integer delFlag) { this.delFlag = delFlag; }
    public Date getDeleteTime() { return deleteTime; }
    public void setDeleteTime(Date deleteTime) { this.deleteTime = deleteTime; }
    public String getDeleteBy() { return deleteBy; }
    public void setDeleteBy(String deleteBy) { this.deleteBy = deleteBy; }
    public Long getDeleteById() { return deleteById; }
    public void setDeleteById(Long deleteById) { this.deleteById = deleteById; }
    public String getNextStep() { return nextStep; }
    public void setNextStep(String nextStep) { this.nextStep = nextStep; }
    public Long getNextOwnerId() { return nextOwnerId; }
    public void setNextOwnerId(Long nextOwnerId) { this.nextOwnerId = nextOwnerId; }
    public String getNextDue() { return nextDue; }
    public void setNextDue(String nextDue) { this.nextDue = nextDue; }
}
