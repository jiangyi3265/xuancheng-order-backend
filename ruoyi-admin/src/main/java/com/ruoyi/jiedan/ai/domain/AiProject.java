package com.ruoyi.jiedan.ai.domain;

import java.util.Date;

/** AI 改码项目配置。仓库凭证始终由 Worker 本机持有，不进入控制面。 */
public class AiProject
{
    private Long id;
    private String name;
    private String repoUrl;
    private String defaultBranch;
    private String provider;
    private String model;
    private String profileKey;
    private Long orderId;
    private String automationMode;
    private String deployWorkflow;
    private Integer deployTimeoutMin;
    private String productionUrl;
    private String validationCommands;
    private String forbiddenPaths;
    private Integer maxParallel;
    private Integer enabled;
    private Integer version;
    private String createdBy;
    private Date createTime;
    private String updatedBy;
    private Date updateTime;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getRepoUrl() { return repoUrl; }
    public void setRepoUrl(String repoUrl) { this.repoUrl = repoUrl; }
    public String getDefaultBranch() { return defaultBranch; }
    public void setDefaultBranch(String defaultBranch) { this.defaultBranch = defaultBranch; }
    public String getProvider() { return provider; }
    public void setProvider(String provider) { this.provider = provider; }
    public String getModel() { return model; }
    public void setModel(String model) { this.model = model; }
    public String getProfileKey() { return profileKey; }
    public void setProfileKey(String profileKey) { this.profileKey = profileKey; }
    public Long getOrderId() { return orderId; }
    public void setOrderId(Long orderId) { this.orderId = orderId; }
    public String getAutomationMode() { return automationMode; }
    public void setAutomationMode(String automationMode) { this.automationMode = automationMode; }
    public String getDeployWorkflow() { return deployWorkflow; }
    public void setDeployWorkflow(String deployWorkflow) { this.deployWorkflow = deployWorkflow; }
    public Integer getDeployTimeoutMin() { return deployTimeoutMin; }
    public void setDeployTimeoutMin(Integer deployTimeoutMin) { this.deployTimeoutMin = deployTimeoutMin; }
    public String getProductionUrl() { return productionUrl; }
    public void setProductionUrl(String productionUrl) { this.productionUrl = productionUrl; }
    public String getValidationCommands() { return validationCommands; }
    public void setValidationCommands(String validationCommands) { this.validationCommands = validationCommands; }
    public String getForbiddenPaths() { return forbiddenPaths; }
    public void setForbiddenPaths(String forbiddenPaths) { this.forbiddenPaths = forbiddenPaths; }
    public Integer getMaxParallel() { return maxParallel; }
    public void setMaxParallel(Integer maxParallel) { this.maxParallel = maxParallel; }
    public Integer getEnabled() { return enabled; }
    public void setEnabled(Integer enabled) { this.enabled = enabled; }
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
