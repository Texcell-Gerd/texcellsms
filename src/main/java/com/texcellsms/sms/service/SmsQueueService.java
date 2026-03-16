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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static com.texcellsms.sms.util.UserUtils.getCurrentSubAccount;

@Service
@RequiredArgsConstructor
public class SmsQueueService {

    private final SmsQueueMapper smsQueueMapper;

    // 获取当前登录用户的subAccount
    private String SubAccount(){
        return TokenService.getUsername();
    }

    // ==================== 任务列表（分页） ====================
    public Map<String, Object> getQueueTasks(Integer pageNum, Integer pageSize) {
        try {
            DataSourceInterceptor.setCurrentDataSource(DynamicDataSourceConfig.DS_WEB_PORTAL);

            String subAccount = SubAccount();
            String threeDaysAgoTime = TimeRangeUtils.getThreeDaysAgoTime();
            int offset = (pageNum - 1) * pageSize;

            List<ScheduleTaskVO> list = smsQueueMapper.selectScheduleTaskList(
                    subAccount, threeDaysAgoTime, offset, pageSize
            );

            // ==============================
            // 🔥 只统计 DELIVRD / UNKNOWN
            // ==============================
            if (list != null && !list.isEmpty()) {
                List<String> taskIds = list.stream()
                        .map(ScheduleTaskVO::getTaskId)
                        .collect(Collectors.toList());

                List<Map<String, Object>> drStats = smsQueueMapper.selectDrStatsByTaskIds(taskIds);

                Map<String, Map<String, Integer>> statMap = new HashMap<>();
                for (Map<String, Object> map : drStats) {
                    String taskId = (String) map.get("APITaskId");
                    String status = (String) map.get("RecvDRStatus");
                    Integer count = ((Number) map.get("count")).intValue();

                    Map<String, Integer> statusCount = statMap.getOrDefault(taskId, new HashMap<>());

                    // ✅ 只统计这两个
                    if ("DELIVRD".equals(status)) {
                        statusCount.put("DELIVRD", count);
                    } else if ("UNKNOWN".equals(status)) {
                        statusCount.put("UNKNOWN", count);
                    }

                    statMap.put(taskId, statusCount);
                }

                // 赋值到返回对象
                for (ScheduleTaskVO vo : list) {
                    Map<String, Integer> statusCount = statMap.getOrDefault(vo.getTaskId(), new HashMap<>());

                    // 赋值两个字段
                    vo.setDrSuccCount(statusCount.getOrDefault("DELIVRD", 0));
                    vo.setDrFailCount(statusCount.getOrDefault("UNKNOWN", 0));

                    // 短信加密
                    vo.setShortMessage(Base64Util.encrypt(vo.getShortMessage()));
                }
            }

            Long total = smsQueueMapper.countScheduleTaskList(subAccount, threeDaysAgoTime);

            Map<String, Object> result = new HashMap<>();
            result.put("records", list);
            result.put("total", total);
            result.put("pageNum", pageNum);
            result.put("pageSize", pageSize);
            return result;

        } finally {
            // 不清理，交给拦截器处理
        }
    }

    // ==================== 任务详情（分页） ====================
    public Map<String, Object> getQueueTaskDetail(String taskId, Integer pageNum, Integer pageSize, String callee) {
        try {
            DataSourceInterceptor.setCurrentDataSource(DynamicDataSourceConfig.DS_WEB_PORTAL);

            int offset = (pageNum - 1) * pageSize;
            List<TaskDetailVO> list = smsQueueMapper.selectTaskDetailList(taskId, callee, offset, pageSize);
            Long total = smsQueueMapper.countTaskDetailList(taskId, callee);

            Map<String, Object> result = new HashMap<>();
            result.put("records", list);
            result.put("total", total);
            result.put("pageNum", pageNum);
            result.put("pageSize", pageSize);
            return result;
        } finally {
            // 不再手动清理，由拦截器统一处理
        }
    }

    // ==================== 导出全部详情 ====================
    public void exportAllTaskDetailsByTaskId(String taskId, HttpServletResponse response) throws Exception {
        try {
            DataSourceInterceptor.setCurrentDataSource(DynamicDataSourceConfig.DS_WEB_PORTAL);

            List<TblAPITask> entityList = smsQueueMapper.selectAllTaskDetailsByTaskId(taskId);
            List<TaskDetailExcelVO> excelList = entityList.stream()
                    .map(e -> {
                        TaskDetailExcelVO vo = new TaskDetailExcelVO();
                        BeanUtils.copyProperties(e, vo);
                        return vo;
                    })
                    .collect(Collectors.toList());

            String fileName = URLEncoder.encode("队列任务详情_" + taskId, StandardCharsets.UTF_8.name()).replaceAll("\\+", "%20");
            response.setContentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
            response.setCharacterEncoding("UTF-8");
            response.setHeader("Content-Disposition", "attachment;filename*=UTF-8''" + fileName + ".xlsx");

            EasyExcel.write(response.getOutputStream(), TaskDetailExcelVO.class)
                    .sheet("任务详情")
                    .doWrite(excelList);
        } finally {
            // 不再手动清理，由拦截器统一处理
        }
    }

    // ========== 批量修改状态（伪删除） ==========
    public int batchDeleteTask(Set<String> taskIds) {
        try {
            DataSourceInterceptor.setCurrentDataSource(DynamicDataSourceConfig.DS_WEB_PORTAL);

            if (taskIds == null || taskIds.isEmpty()) {
                return 0;
            }
            String subAccount = SubAccount();
            String threeDaysAgo = TimeRangeUtils.getThreeDaysAgoTime();
            return smsQueueMapper.batchUpdateScheduleStatus(subAccount, threeDaysAgo, taskIds);
        } finally {
            // 不再手动清理，由拦截器统一处理
        }
    }
}