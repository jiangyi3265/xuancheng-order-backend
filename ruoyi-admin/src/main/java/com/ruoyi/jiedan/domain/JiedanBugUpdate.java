package com.ruoyi.jiedan.domain;

import java.util.Date;

/**
 * Bug 追加 QA / 变更记录。
 */
public class JiedanBugUpdate
{
    private Long id;
    private Long bugId;
    private String content;
    /** 附件（JSON 字符串） */
    private String attachments;
    private String createdBy;
    private Date createTime;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getBugId() { return bugId; }
    public void setBugId(Long bugId) { this.bugId = bugId; }
    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }
    public String getAttachments() { return attachments; }
    public void setAttachments(String attachments) { this.attachments = attachments; }
    public String getCreatedBy() { return createdBy; }
    public void setCreatedBy(String createdBy) { this.createdBy = createdBy; }
    public Date getCreateTime() { return createTime; }
    public void setCreateTime(Date createTime) { this.createTime = createTime; }
}
