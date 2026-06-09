package com.texcellsms.sms.model.entity;

import lombok.Data;
import java.time.LocalDateTime;

/**
 * 监控系统用户实体
 * 对应表名：tbl_monitor_user
 */
@Data
public class MonitorUser {

    /**
     * 主键ID
     */
    private Long id;

    /**
     * 用户名
     */
    private String username;

    /**
     * 密码（明文）
     */
    private String password;

    /**
     * 角色：ADMIN-管理员
     */
    private String role;

    /**
     * 状态：0-禁用，1-启用
     */
    private Integer status;

    /**
     * 允许访问的IP，多个用逗号分隔，为空则允许所有内网IP
     */
    private String allowedIps;

    /**
     * 备注
     */
    private String remark;

    /**
     * 创建时间
     */
    private LocalDateTime createTime;

    /**
     * 更新时间
     */
    private LocalDateTime updateTime;
}