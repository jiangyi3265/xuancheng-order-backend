package com.ruoyi.jiedan.ai.domain;

import java.util.Date;

/** 一次业务改码任务；每次重试会生成不可变的 AiAttempt。 */
public class AiTask
{
    private Long id;
    private Long projectId;
    private Long orderId;
    private Long bugId;
    private String title;
    private String prompt;
    private String promptHash;
    private Integer promptVersion;
    private String riskLevel;
    private String status;
    private Long currentAttemptId;
    private Long approvedAttemptId;
    private String approvedHeadSha;
    private String approvedDiffSha;
    private Integer version;
    private String createdBy;
    private Date createTime;
    private String updatedBy;
    private Date updateTime;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getProjectId() { return projectId; }
    public void setProjectId(Long projectId) { this.projectId = projectId; }
    public Long getOrderId() { return orderId; }
    public void setOrderId(Long orderId) { this.orderId = orderId; }
    public Long getBugId() { return bugId; }
    public void setBugId(Long bugId) { this.bugId = bugId; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getPrompt() { return prompt; }
    public void setPrompt(String prompt) { this.prompt = prompt; }
    public String getPromptHash() { return promptHash; }
    public void setPromptHash(String promptHash) { this.promptHash = promptHash; }
    public Integer getPromptVersion() { return promptVersion; }
    public void setPromptVersion(Integer promptVersion) { this.promptVersion = promptVersion; }
    public String getRiskLevel() { return riskLevel; }
    public void setRiskLevel(String riskLevel) { this.riskLevel = riskLevel; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public Long getCurrentAttemptId() { return currentAttemptId; }
    public void setCurrentAttemptId(Long currentAttemptId) { this.currentAttemptId = currentAttemptId; }
    public Long getApprovedAttemptId() { return approvedAttemptId; }
    public void setApprovedAttemptId(Long approvedAttemptId) { this.approvedAttemptId = approvedAttemptId; }
    public String getApprovedHeadSha() { return approvedHeadSha; }
    public void setApprovedHeadSha(String approvedHeadSha) { this.approvedHeadSha = approvedHeadSha; }
    public String getApprovedDiffSha() { return approvedDiffSha; }
    public void setApprovedDiffSha(String approvedDiffSha) { this.approvedDiffSha = approvedDiffSha; }
    public Integer getVersion() { return version; }
    public void setVersion(Integer version) { this.version = version; }
    public String getCreatedBy() { return createdBy; }
    public void setCreatedBy(String createdBy) { this.createdBy = createdBy; }
    public Date getCreateTime() { return createTime; }
    public void setCreateTime(Date createTime) { this.createTime = createTime; }
    public String getUpdatedBy() { return updatedBy; }
    public void setUpdatedBy(String updatedBy) { this.updatedBy = updatedBy; }
    public Date getUpdateTime() { return updateTime; }
    public void setUpdateTime(Date updateTime) { this.updateTime = updateTime; }
}
