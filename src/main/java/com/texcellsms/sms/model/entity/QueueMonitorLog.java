package com.texcellsms.sms.model.entity;

import java.time.LocalDateTime;

public class QueueMonitorLog {
    private Long id;
    private String taskId;
    private String subAccount;
    private String taskName;
    private Integer totalCount;
    private Integer submitSuccCount;
    private Integer drSuccCount;
    private Integer drFailCount;
    private Integer pendingCount;
    private Integer detailPendingCount;
    private LocalDateTime taskTime;
    private Integer elapsedMinutes;
    private String alertLevel;
    private Integer alertSent;
    private String status;
    private String processedBy;
    private LocalDateTime processedTime;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getTaskId() { return taskId; }
    public void setTaskId(String taskId) { this.taskId = taskId; }
    public String getSubAccount() { return subAccount; }
    public void setSubAccount(String subAccount) { this.subAccount = subAccount; }
    public String getTaskName() { return taskName; }
    public void setTaskName(String taskName) { this.taskName = taskName; }
    public Integer getTotalCount() { return totalCount; }
    public void setTotalCount(Integer totalCount) { this.totalCount = totalCount; }
    public Integer getSubmitSuccCount() { return submitSuccCount; }
    public void setSubmitSuccCount(Integer submitSuccCount) { this.submitSuccCount = submitSuccCount; }
    public Integer getDrSuccCount() { return drSuccCount; }
    public void setDrSuccCount(Integer drSuccCount) { this.drSuccCount = drSuccCount; }
    public Integer getDrFailCount() { return drFailCount; }
    public void setDrFailCount(Integer drFailCount) { this.drFailCount = drFailCount; }
    public Integer getPendingCount() { return pendingCount; }
    public void setPendingCount(Integer pendingCount) { this.pendingCount = pendingCount; }
    public Integer getDetailPendingCount() { return detailPendingCount; }
    public void setDetailPendingCount(Integer detailPendingCount) { this.detailPendingCount = detailPendingCount; }
    public LocalDateTime getTaskTime() { return taskTime; }
    public void setTaskTime(LocalDateTime taskTime) { this.taskTime = taskTime; }
    public Integer getElapsedMinutes() { return elapsedMinutes; }
    public void setElapsedMinutes(Integer elapsedMinutes) { this.elapsedMinutes = elapsedMinutes; }
    public String getAlertLevel() { return alertLevel; }
    public void setAlertLevel(String alertLevel) { this.alertLevel = alertLevel; }
    public Integer getAlertSent() { return alertSent; }
    public void setAlertSent(Integer alertSent) { this.alertSent = alertSent; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getProcessedBy() { return processedBy; }
    public void setProcessedBy(String processedBy) { this.processedBy = processedBy; }
    public LocalDateTime getProcessedTime() { return processedTime; }
    public void setProcessedTime(LocalDateTime processedTime) { this.processedTime = processedTime; }
    public LocalDateTime getCreateTime() { return createTime; }
    public void setCreateTime(LocalDateTime createTime) { this.createTime = createTime; }
    public LocalDateTime getUpdateTime() { return updateTime; }
    public void setUpdateTime(LocalDateTime updateTime) { this.updateTime = updateTime; }
}