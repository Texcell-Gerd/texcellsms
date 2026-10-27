package com.texcellsms.sms.service;

import CacheDB.NCacheDBLink;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

@Service
public class SubTaskService {

    // 客户端查询自己的
    public List<Map<String, String>> queryMySubTasks(String subAccount, String taskId, int limit) {
        NCacheDBLink db = NCacheDBLink.getNCacheDBLink();
        db.InitEnv();
        db.ConnectToCacheDB();
        try {
            return db.prc_QueryAPISubTask(taskId, subAccount, limit);
        } finally {
            db.Disconnect();
            db.Destroy();
        }
    }

    // 管理端查询所有的（subAccount 传空）
    public List<Map<String, String>> queryAllSubTasks(String subAccount, String taskId, int limit) {
        NCacheDBLink db = NCacheDBLink.getNCacheDBLink();
        db.InitEnv();
        db.ConnectToCacheDB();
        try {
            return db.prc_QueryAPISubTask(taskId, subAccount, limit);
        } finally {
            db.Disconnect();
            db.Destroy();
        }
    }

    // 操作子任务
    public int operateSubTask(String action, String taskId, int subIndex, String remark) {
        NCacheDBLink db = NCacheDBLink.getNCacheDBLink();
        db.InitEnv();
        db.ConnectToCacheDB();
        try {
            return db.prc_OperateAPISubTask(action, taskId, subIndex, remark);
        } finally {
            db.Disconnect();
            db.Destroy();
        }
    }
}