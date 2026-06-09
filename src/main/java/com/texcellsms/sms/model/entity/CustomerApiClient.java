package com.texcellsms.sms.model.entity;

import lombok.Data;
import java.time.LocalDateTime;

/**
 * API客户信息实体
 * 对应表名：tbl_api_client
 */
@Data
public class CustomerApiClient {

    /**
     * 主键ID
     */
    private Long id;

    /**
     * 客户账号
     */
    private String username;

    /**
     * 密码
     */
    private String password;

    /**
     * 状态：0-禁用，1-启用
     */
    private Integer status;

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