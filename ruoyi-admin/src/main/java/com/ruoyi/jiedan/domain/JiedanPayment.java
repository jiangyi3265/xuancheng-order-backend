package com.ruoyi.jiedan.domain;

import java.math.BigDecimal;
import java.util.Date;

/**
 * 收款流水 jiedan_payment：每收一笔记一条，订单的“已收金额”是这些流水的合计。
 */
public class JiedanPayment
{
    private Long id;
    private Long orderId;
    private BigDecimal amount;
    /** 收款日期 yyyy-MM-dd（月度收入按它统计） */
    private String payDate;
    /** 备注，如 定金 / 尾款 / 微信转账 */
    private String note;
    private String createdBy;
    private Date createTime;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getOrderId() { return orderId; }
    public void setOrderId(Long orderId) { this.orderId = orderId; }
    public BigDecimal getAmount() { return amount; }
    public void setAmount(BigDecimal amount) { this.amount = amount; }
    public String getPayDate() { return payDate; }
    public void setPayDate(String payDate) { this.payDate = payDate; }
    public String getNote() { return note; }
    public void setNote(String note) { this.note = note; }
    public String getCreatedBy() { return createdBy; }
    public void setCreatedBy(String createdBy) { this.createdBy = createdBy; }
    public Date getCreateTime() { return createTime; }
    public void setCreateTime(Date createTime) { this.createTime = createTime; }
}
