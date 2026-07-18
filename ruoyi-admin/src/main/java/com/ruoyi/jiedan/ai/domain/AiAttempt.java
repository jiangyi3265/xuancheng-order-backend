package com.ruoyi.jiedan.ai.domain;

import java.util.Date;

/** Worker 的一次有租约、有围栏令牌的执行尝试。 */
public class AiAttempt
{
    private Long id;
    private Long taskId;
    private Integer attemptNo;
    private String requestId;
    private String fenceToken;
    private String workerId;
    private String provider;
    private String model;
    private String status;
    private Date leaseExpireTime;
    private String branchName;
    private String baseSha;
    private String headSha;
    private String treeSha;
    private String diffSha;
    private String prUrl;
    private Long prNumber;
    private Integer policyPassed;
    private Integer validationPassed;
    private String validationSummary;
    private String errorCode;
    private String errorMessage;
    private Date startedAt;
    private Date finishedAt;
    private Date createTime;
    private Date updateTime;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getTaskId() { return taskId; }
    public void setTaskId(Long taskId) { this.taskId = taskId; }
    public Integer getAttemptNo() { return attemptNo; }
    public void setAttemptNo(Integer attemptNo) { this.attemptNo = attemptNo; }
    public String getRequestId() { return requestId; }
    public void setRequestId(String requestId) { this.requestId = requestId; }
    public String getFenceToken() { return fenceToken; }
    public void setFenceToken(String fenceToken) { this.fenceToken = fenceToken; }
    public String getWorkerId() { return workerId; }
    public void setWorkerId(String workerId) { this.workerId = workerId; }
    public String getProvider() { return provider; }
    public void setProvider(String provider) { this.provider = provider; }
    public String getModel() { return model; }
    public void setModel(String model) { this.model = model; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public Date getLeaseExpireTime() { return leaseExpireTime; }
    public void setLeaseExpireTime(Date leaseExpireTime) { this.leaseExpireTime = leaseExpireTime; }
    public String getBranchName() { return branchName; }
    public void setBranchName(String branchName) { this.branchName = branchName; }
    public String getBaseSha() { return baseSha; }
    public void setBaseSha(String baseSha) { this.baseSha = baseSha; }
    public String getHeadSha() { return headSha; }
    public void setHeadSha(String headSha) { this.headSha = headSha; }
    public String getTreeSha() { return treeSha; }
    public void setTreeSha(String treeSha) { this.treeSha = treeSha; }
    public String getDiffSha() { return diffSha; }
    public void setDiffSha(String diffSha) { this.diffSha = diffSha; }
    public String getPrUrl() { return prUrl; }
    public void setPrUrl(String prUrl) { this.prUrl = prUrl; }
    public Long getPrNumber() { return prNumber; }
    public void setPrNumber(Long prNumber) { this.prNumber = prNumber; }
    public Integer getPolicyPassed() { return policyPassed; }
    public void setPolicyPassed(Integer policyPassed) { this.policyPassed = policyPassed; }
    public Integer getValidationPassed() { return validationPassed; }
    public void setValidationPassed(Integer validationPassed) { this.validationPassed = validationPassed; }
    public String getValidationSummary() { return validationSummary; }
    public void setValidationSummary(String validationSummary) { this.validationSummary = validationSummary; }
    public String getErrorCode() { return errorCode; }
    public void setErrorCode(String errorCode) { this.errorCode = errorCode; }
    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }
    public Date getStartedAt() { return startedAt; }
    public void setStartedAt(Date startedAt) { this.startedAt = startedAt; }
    public Date getFinishedAt() { return finishedAt; }
    public void setFinishedAt(Date finishedAt) { this.finishedAt = finishedAt; }
    public Date getCreateTime() { return createTime; }
    public void setCreateTime(Date createTime) { this.createTime = createTime; }
    public Date getUpdateTime() { return updateTime; }
    public void setUpdateTime(Date updateTime) { this.updateTime = updateTime; }
}
