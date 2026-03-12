package com.texcellsms.sms.service;

import com.alibaba.excel.EasyExcel;
import com.texcellsms.sms.constant.DataSourceConstants;
import com.texcellsms.sms.constant.DataSourceInterceptor;
import com.texcellsms.sms.mapper.webportal.SmsQueueMapper;
import com.texcellsms.sms.model.entity.TblAPITask;
import com.texcellsms.sms.model.vo.ScheduleTaskVO;
import com.texcellsms.sms.model.vo.TaskDetailExcelVO;
import com.texcellsms.sms.model.vo.TaskDetailVO;
import com.texcellsms.sms.util.Base64Util;
import com.texcellsms.sms.util.TimeRangeUtils;
import com.texcellsms.sms.util.UserUtils;
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

@Service
@RequiredArgsConstructor
public class SmsQueueService {

    private final SmsQueueMapper smsQueueMapper;

    // 获取当前登录用户的subAccount
    private String SubAccount(){
        return "WTest1";
    }

    // ==================== 任务列表（分页） ====================
    public Map<String, Object> getQueueTasks(Integer pageNum, Integer pageSize) {
        // 1. 安全切换数据源（使用常量类，避免硬编码）
        try {
            DataSourceInterceptor.setCurrentDataSource(DataSourceConstants.DS_WEB_PORTAL);

            String subAccount = SubAccount();

            String threeDaysAgoTime = TimeRangeUtils.getThreeDaysAgoTime();

            int offset = (pageNum - 1) * pageSize;

            List<ScheduleTaskVO> list = smsQueueMapper.selectScheduleTaskList(
                    subAccount, threeDaysAgoTime, offset, pageSize
            );

            if (list != null && !list.isEmpty()) {
                for (ScheduleTaskVO vo : list) {
                    String encryptedMsg = Base64Util.encrypt(vo.getShortMessage());
                    vo.setShortMessage(encryptedMsg);
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
            // 2. 仅清空当前方法设置的数据源，交给拦截器最终清理（避免重复清理）
            // 注意：这里不再调用clear，改为由拦截器统一清理，防止提前清理导致后续操作失效
            // DataSourceInterceptor.clearCurrentDataSource();
        }
    }

    // ==================== 任务详情（分页） ====================
    public Map<String, Object> getQueueTaskDetail(String taskId, Integer pageNum, Integer pageSize, String callee) {
        try {
            DataSourceInterceptor.setCurrentDataSource(DataSourceConstants.DS_WEB_PORTAL);

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
            DataSourceInterceptor.setCurrentDataSource(DataSourceConstants.DS_WEB_PORTAL);

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
            DataSourceInterceptor.setCurrentDataSource(DataSourceConstants.DS_WEB_PORTAL);

            if (taskIds == null || taskIds.isEmpty()) {
                return 0;
            }
            String subAccount = UserUtils.getCurrentSubAccount();
            String threeDaysAgo = TimeRangeUtils.getThreeDaysAgoTime();
            return smsQueueMapper.batchUpdateScheduleStatus(subAccount, threeDaysAgo, taskIds);
        } finally {
            // 不再手动清理，由拦截器统一处理
        }
    }
}