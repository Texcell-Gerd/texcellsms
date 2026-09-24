package com.texcellsms.sms.model.entity;

import com.fasterxml.jackson.annotation.JsonAlias;
import lombok.Data;

import java.time.LocalDateTime;

@Data
public class ApiContentRewrite {

    @JsonAlias("UNID")
    private Integer unid;

    @JsonAlias("RuleName")
    private String ruleName;

    @JsonAlias("MCC")
    private String mcc;

    @JsonAlias("MNC")
    private String mnc;

    @JsonAlias("SubAccount")
    private String subAccount;

    @JsonAlias("MatchPattern")
    private String matchPattern;

    @JsonAlias("ReplaceTemplate")
    private String replaceTemplate;

    @JsonAlias("Priority")
    private Integer priority;

    @JsonAlias("IgnoreCase")
    private Integer ignoreCase;

    @JsonAlias("GlobalReplace")
    private Integer globalReplace;

    @JsonAlias("StopOnMatch")
    private Integer stopOnMatch;

    @JsonAlias("RandNoRepeat")
    private Integer randNoRepeat;

    @JsonAlias("Enabled")
    private Integer enabled;

    @JsonAlias("UpdateSeq")
    private Integer updateSeq;

    @JsonAlias("Remark")
    private String remark;

    @JsonAlias("CreateTime")
    private LocalDateTime createTime;

    @JsonAlias("UpdateTime")
    private LocalDateTime updateTime;
}
