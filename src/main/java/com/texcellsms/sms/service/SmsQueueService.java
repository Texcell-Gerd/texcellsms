package com.texcellsms.sms.service;

import com.alibaba.excel.EasyExcel;
import com.texcellsms.sms.config.DynamicDataSourceConfig;
import com.texcellsms.sms.constant.DataSourceInterceptor;
import com.texcellsms.sms.mapper.webportal.SmsQueueMapper;
import com.texcellsms.sms.model.entity.TblAPITask;
import com.texcellsms.sms.model.vo.ScheduleTaskVO;
import com.texcellsms.sms.model.vo.TaskDetailExcelVO;
import com.texcellsms.sms.model.vo.TaskDetailVO;
import com.texcellsms.sms.util.Base64Util;
import com.texcellsms.sms.util.TimeRangeUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;

import javax.servlet.http.HttpServletResponse;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class SmsQueueService {

    private final SmsQueueMapper smsQueueMapper;

    /**
     * 获取当前登录用户账号
     */
    private String SubAccount() {
        return TokenService.getUsername();
    }

    /**
     * 分页获取短信队列任务列表
     */
    public Map<String, Object> getQueueTasks(Integer pageNum, Integer pageSize) {
        DataSourceInterceptor.setCurrentDataSource(DynamicDataSourceConfig.DS_WEB_PORTAL);

        String subAccount = SubAccount();
        String threeDaysAgoTime = TimeRangeUtils.getThreeDaysAgoTime();
        int offset = (pageNum - 1) * pageSize;

        List<ScheduleTaskVO> list = smsQueueMapper.selectScheduleTaskList(
                subAccount, threeDaysAgoTime, offset, pageSize
        );

        // 统计 DR 成功/失败数量
        if (list != null && !list.isEmpty()) {
            List<String> taskIds = list.stream()
                    .map(ScheduleTaskVO::getTaskId)
                    .filter(Objects::nonNull)
                    .collect(Collectors.toList());

            List<Map<String, Object>> drStats = smsQueueMapper.selectDrStatsByTaskIds(taskIds);

            Map<String, Map<String, Integer>> statMap = new HashMap<>();
            for (Map<String, Object> map : drStats) {
                String taskId = (String) map.get("APITaskId");
                Integer succCount = ((Number) map.get("drSuccCount")).intValue();
                Integer failCount = ((Number) map.get("drFailCount")).intValue();

                Map<String, Integer> countMap = new HashMap<>();
                countMap.put("drSuccCount", succCount);
                countMap.put("drFailCount", failCount);
                statMap.put(taskId, countMap);
            }

            // 赋值统计数据
            for (ScheduleTaskVO vo : list) {
                if (vo == null) continue;

                Map<String, Integer> countMap = statMap.getOrDefault(vo.getTaskId(), new HashMap<>());
                vo.setDrSuccCount(countMap.getOrDefault("drSuccCount", 0));
                vo.setDrFailCount(countMap.getOrDefault("drFailCount", 0));

                // 短信内容加密
                vo.setShortMessage(Base64Util.encrypt(vo.getShortMessage()));
            }
        }

        Long total = smsQueueMapper.countScheduleTaskList(subAccount, threeDaysAgoTime);

        Map<String, Object> result = new HashMap<>();
        result.put("records", list == null ? Collections.emptyList() : list);
        result.put("total", total == null ? 0L : total);
        result.put("pageNum", pageNum);
        result.put("pageSize", pageSize);
        return result;
    }

    /**
     * 分页获取任务详情
     */
    public Map<String, Object> getQueueTaskDetail(String taskId, Integer pageNum, Integer pageSize, String callee) {
        DataSourceInterceptor.setCurrentDataSource(DynamicDataSourceConfig.DS_WEB_PORTAL);

        int offset = (pageNum - 1) * pageSize;
        List<TaskDetailVO> list = smsQueueMapper.selectTaskDetailList(taskId, callee, offset, pageSize);
        Long total = smsQueueMapper.countTaskDetailList(taskId, callee);

        Map<String, Object> result = new HashMap<>();
        result.put("records", list == null ? Collections.emptyList() : list);
        result.put("total", total == null ? 0L : total);
        result.put("pageNum", pageNum);
        result.put("pageSize", pageSize);
        return result;
    }

    /**
     * 导出任务全部详情（Excel）
     */
    public void exportAllTaskDetailsByTaskId(String taskId, HttpServletResponse response) throws Exception {
        DataSourceInterceptor.setCurrentDataSource(DynamicDataSourceConfig.DS_WEB_PORTAL);

        List<TblAPITask> entityList = smsQueueMapper.selectAllTaskDetailsByTaskId(taskId);

        // 空数据防护，避免导出空白文件
        if (entityList == null) {
            entityList = Collections.emptyList();
        }

        List<TaskDetailExcelVO> excelList = entityList.stream()
                .filter(Objects::nonNull)
                .map(e -> {
                    TaskDetailExcelVO vo = new TaskDetailExcelVO();
                    BeanUtils.copyProperties(e, vo);
                    return vo;
                })
                .collect(Collectors.toList());

        String fileName = URLEncoder.encode("Queue Task Details_" + taskId, StandardCharsets.UTF_8.name()).replaceAll("\\+", "%20");
        response.setContentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        response.setCharacterEncoding("UTF-8");
        response.setHeader("Content-Disposition", "attachment;filename*=UTF-8''" + fileName + ".xlsx");

        EasyExcel.write(response.getOutputStream(), TaskDetailExcelVO.class)
                .sheet("Task Details")
                .doWrite(excelList);
    }

    /**
     * 批量删除任务（伪删除：修改状态）
     */
    public int batchDeleteTask(Set<String> taskIds) {
        DataSourceInterceptor.setCurrentDataSource(DynamicDataSourceConfig.DS_WEB_PORTAL);

        if (taskIds == null || taskIds.isEmpty()) {
            return 0;
        }

        // 过滤空字符串
        taskIds = taskIds.stream()
                .filter(id -> id != null && !id.trim().isEmpty())
                .collect(Collectors.toSet());

        if (taskIds.isEmpty()) {
            return 0;
        }

        String subAccount = SubAccount();
        String threeDaysAgo = TimeRangeUtils.getThreeDaysAgoTime();
        return smsQueueMapper.batchUpdateScheduleStatus(subAccount, threeDaysAgo, taskIds);
    }

    /**
     * 批量删除任务（伪删除）
     * 内部人员使用，可操作所有客户的预约任务，仅限最近3天数据
     * 只能删除状态为 0（待发送）的任务
     */
    public int batchDeleteTaskForAdmin(Set<String> taskIds) {
        DataSourceInterceptor.setCurrentDataSource(DynamicDataSourceConfig.DS_WEB_PORTAL);

        if (taskIds == null || taskIds.isEmpty()) {
            return 0;
        }

        // 过滤空字符串
        taskIds = taskIds.stream()
                .filter(id -> id != null && !id.trim().isEmpty())
                .collect(Collectors.toSet());

        if (taskIds.isEmpty()) {
            return 0;
        }

        String threeDaysAgoTime = TimeRangeUtils.getThreeDaysAgoTime();

        return smsQueueMapper.batchUpdateScheduleStatusForAdmin(taskIds, threeDaysAgoTime);
    }

    /**
     * 内部管理 - 分页获取所有客户的任务列表
     */
    public Map<String, Object> getAllQueueTasksForAdmin(String subAccount, String taskId, String taskIp,
                                                        Integer scheduleStatus,
                                                        Integer pageNum, Integer pageSize) {
        DataSourceInterceptor.setCurrentDataSource(DynamicDataSourceConfig.DS_WEB_PORTAL);

        String threeDaysAgoTime = TimeRangeUtils.getThreeDaysAgoTime();
        int offset = (pageNum - 1) * pageSize;

        List<ScheduleTaskVO> list = smsQueueMapper.selectAllScheduleTaskList(
                subAccount, taskId, taskIp, scheduleStatus, threeDaysAgoTime, offset, pageSize
        );

        // 统计 DR 成功/失败数量
        if (list != null && !list.isEmpty()) {
            List<String> taskIds = list.stream()
                    .map(ScheduleTaskVO::getTaskId)
                    .filter(Objects::nonNull)
                    .collect(Collectors.toList());

            List<Map<String, Object>> drStats = smsQueueMapper.selectDrStatsByTaskIds(taskIds);

            Map<String, Map<String, Integer>> statMap = new HashMap<>();
            for (Map<String, Object> map : drStats) {
                String taskIdVal = (String) map.get("APITaskId");
                Integer succCount = ((Number) map.get("drSuccCount")).intValue();
                Integer failCount = ((Number) map.get("drFailCount")).intValue();

                Map<String, Integer> countMap = new HashMap<>();
                countMap.put("drSuccCount", succCount);
                countMap.put("drFailCount", failCount);
                statMap.put(taskIdVal, countMap);
            }

            for (ScheduleTaskVO vo : list) {
                Map<String, Integer> countMap = statMap.getOrDefault(vo.getTaskId(), new HashMap<>());
                vo.setDrSuccCount(countMap.getOrDefault("drSuccCount", 0));
                vo.setDrFailCount(countMap.getOrDefault("drFailCount", 0));
                vo.setShortMessage(Base64Util.encrypt(vo.getShortMessage()));
            }
        }

        Long total = smsQueueMapper.countAllScheduleTaskList(subAccount, taskId, taskIp, scheduleStatus, threeDaysAgoTime);

        Map<String, Object> result = new HashMap<>();
        result.put("records", list == null ? Collections.emptyList() : list);
        result.put("total", total == null ? 0L : total);
        result.put("pageNum", pageNum);
        result.put("pageSize", pageSize);
        return result;
    }

    public List<Map<String, Object>> getCustomerRankingLastHalfHour() {
        DataSourceInterceptor.setCurrentDataSource(DynamicDataSourceConfig.DS_WEB_PORTAL);
        return smsQueueMapper.getCustomerRankingLastHalfHour();
    }
}