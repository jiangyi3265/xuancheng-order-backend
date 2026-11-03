package com.ruoyi.jiedan.domain;

import java.util.Date;

/**
 * 员工申请 jiedan_approval：员工的敏感操作（改报价 / 取消 / 删除 / 收款）先提交申请，老板批准后才生效。
 */
public class JiedanApproval
{
    public static final String TYPE_AMOUNT = "amount";
    public static final String TYPE_CANCEL = "cancel";
    public static final String TYPE_DELETE = "delete";
    public static final String TYPE_PAYMENT = "payment";

    public static final String STATUS_PENDING = "pending";
    public static final String STATUS_APPROVED = "approved";
    public static final String STATUS_REJECTED = "rejected";
    public static final String STATUS_WITHDRAWN = "withdrawn";

    private Long id;
    private Long orderId;
    private String type;
    /** 申请内容（JSON 字符串） */
    private String payload;
    private String reason;
    private String status;
    private Long requesterId;
    private String requesterName;
    private Date createTime;
    private Long handlerId;
    private String handlerName;
    private String handleNote;
    private Date handleTime;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getOrderId() { return orderId; }
    public void setOrderId(Long orderId) { this.orderId = orderId; }
    public String getType() { return type; }
    public void setType(String type) { this.type = type; }
    public String getPayload() { return payload; }
    public void setPayload(String payload) { this.payload = payload; }
    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public Long getRequesterId() { return requesterId; }
    public void setRequesterId(Long requesterId) { this.requesterId = requesterId; }
    public String getRequesterName() { return requesterName; }
    public void setRequesterName(String requesterName) { this.requesterName = requesterName; }
    public Date getCreateTime() { return createTime; }
    public void setCreateTime(Date createTime) { this.createTime = createTime; }
    public Long getHandlerId() { return handlerId; }
    public void setHandlerId(Long handlerId) { this.handlerId = handlerId; }
    public String getHandlerName() { return handlerName; }
    public void setHandlerName(String handlerName) { this.handlerName = handlerName; }
    public String getHandleNote() { return handleNote; }
    public void setHandleNote(String handleNote) { this.handleNote = handleNote; }
    public Date getHandleTime() { return handleTime; }
    public void setHandleTime(Date handleTime) { this.handleTime = handleTime; }
}
