package com.texcellsms.sms.service;

import com.alibaba.excel.EasyExcel;
import com.texcellsms.sms.mapper.prepay.SmsQueueMapper;
import com.texcellsms.sms.model.entity.TblAPITask;
import com.texcellsms.sms.model.vo.ScheduleTaskVO;
import com.texcellsms.sms.model.vo.TaskDetailExcelVO;
import com.texcellsms.sms.model.vo.TaskDetailVO;
import com.texcellsms.sms.util.TimeRangeUtils;
import com.texcellsms.sms.util.UserUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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

    // ==================== 任务列表（分页） ====================
    public Map<String, Object> getQueueTasks(Integer pageNum, Integer pageSize, String taskName, Byte scheduleStatus) {
        // 1. 获取当前登录用户的subAccount
        String subAccount = UserUtils.getCurrentSubAccount();
        // 2. 获取3天前的时间
        String threeDaysAgoTime = TimeRangeUtils.getThreeDaysAgoTime();
        // 3. 计算分页偏移量
        int offset = (pageNum - 1) * pageSize;
        // 4. 查询数据
        List<ScheduleTaskVO> list = smsQueueMapper.selectScheduleTaskList(
                subAccount, threeDaysAgoTime, taskName, scheduleStatus, offset, pageSize
        );
        Long total = smsQueueMapper.countScheduleTaskList(
                subAccount, threeDaysAgoTime, taskName, scheduleStatus
        );
        // 5. 封装返回结果
        Map<String, Object> result = new HashMap<>();
        result.put("records", list);
        result.put("total", total);
        result.put("pageNum", pageNum);
        result.put("pageSize", pageSize);
        return result;
    }

    // ==================== 任务详情（分页） ====================
    public Map<String, Object> getQueueTaskDetail(String taskId, Integer pageNum, Integer pageSize, String callee) {
        String subAccount = UserUtils.getCurrentSubAccount();
        String threeDaysAgoTime = TimeRangeUtils.getThreeDaysAgoTime();
        int offset = (pageNum - 1) * pageSize;

        List<TaskDetailVO> list = smsQueueMapper.selectTaskDetailList(
                subAccount, threeDaysAgoTime, taskId, callee, offset, pageSize
        );
        Long total = smsQueueMapper.countTaskDetailList(
                subAccount, threeDaysAgoTime, taskId, callee
        );

        Map<String, Object> result = new HashMap<>();
        result.put("records", list);
        result.put("total", total);
        result.put("pageNum", pageNum);
        result.put("pageSize", pageSize);
        return result;
    }

    // ==================== 导出全部详情 ====================
    public void exportAllTaskDetailsByTaskId(String taskId, HttpServletResponse response) throws Exception {
        String subAccount = UserUtils.getCurrentSubAccount();
        String threeDaysAgoTime = TimeRangeUtils.getThreeDaysAgoTime();

        // 查询符合条件的数据
        List<TblAPITask> entityList = smsQueueMapper.selectAllTaskDetailsByTaskId(
                subAccount, threeDaysAgoTime, taskId
        );
        // 转换为Excel VO
        List<TaskDetailExcelVO> excelList = entityList.stream()
                .map(e -> {
                    TaskDetailExcelVO vo = new TaskDetailExcelVO();
                    BeanUtils.copyProperties(e, vo);
                    return vo;
                })
                .collect(Collectors.toList());

        // 设置导出响应头
        String fileName = URLEncoder.encode("队列任务详情_" + taskId, StandardCharsets.UTF_8.name()).replaceAll("\\+", "%20");
        response.setContentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        response.setCharacterEncoding("UTF-8");
        response.setHeader("Content-Disposition", "attachment;filename*=UTF-8''" + fileName + ".xlsx");

        // 写入Excel
        EasyExcel.write(response.getOutputStream(), TaskDetailExcelVO.class)
                .sheet("任务详情")
                .doWrite(excelList);
    }

    // ========== 新增：批量修改状态（伪删除） ==========
    /**
     * 批量修改任务状态为4（前端显示删除，后台仅改状态）
     * @param taskIds 任务ID集合
     * @return 修改成功的数量
     */
    public int batchDeleteTask(Set<String> taskIds) {
        if (taskIds == null || taskIds.isEmpty()) {
            return 0;
        }
        String subAccount = UserUtils.getCurrentSubAccount();
        String threeDaysAgo = TimeRangeUtils.getThreeDaysAgoTime();
        return smsQueueMapper.batchUpdateScheduleStatus(subAccount, threeDaysAgo, taskIds);
    }
}