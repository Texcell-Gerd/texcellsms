package com.texcellsms.sms.mapper.webportal;

import com.texcellsms.sms.model.DTO.ApiContentRewriteSaveResult;
import com.texcellsms.sms.model.entity.ApiContentRewrite;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Result;
import org.apache.ibatis.annotations.ResultMap;
import org.apache.ibatis.annotations.Results;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.mapping.StatementType;

import java.util.List;

@Mapper
public interface ApiContentRewriteMapper {

    @Results(id = "apiContentRewriteMap", value = {
            @Result(column = "UNID", property = "unid"),
            @Result(column = "RuleName", property = "ruleName"),
            @Result(column = "MCC", property = "mcc"),
            @Result(column = "MNC", property = "mnc"),
            @Result(column = "SubAccount", property = "subAccount"),
            @Result(column = "MatchPattern", property = "matchPattern"),
            @Result(column = "ReplaceTemplate", property = "replaceTemplate"),
            @Result(column = "Priority", property = "priority"),
            @Result(column = "IgnoreCase", property = "ignoreCase"),
            @Result(column = "GlobalReplace", property = "globalReplace"),
            @Result(column = "StopOnMatch", property = "stopOnMatch"),
            @Result(column = "RandNoRepeat", property = "randNoRepeat"),
            @Result(column = "Enabled", property = "enabled"),
            @Result(column = "UpdateSeq", property = "updateSeq"),
            @Result(column = "Remark", property = "remark"),
            @Result(column = "CreateTime", property = "createTime"),
            @Result(column = "UpdateTime", property = "updateTime")
    })
    @Select("<script>" +
            "SELECT UNID, RuleName, MCC, MNC, SubAccount, MatchPattern, ReplaceTemplate, " +
            "Priority, IgnoreCase, GlobalReplace, StopOnMatch, RandNoRepeat, Enabled, " +
            "UpdateSeq, Remark, CreateTime, UpdateTime " +
            "FROM tbl_APIContentRewrite " +
            "<where>" +
            "<if test='ruleName != null and ruleName != \"\"'>AND RuleName LIKE CONCAT('%', #{ruleName}, '%')</if>" +
            "<if test='mcc != null and mcc != \"\"'>AND MCC = #{mcc}</if>" +
            "<if test='mnc != null and mnc != \"\"'>AND MNC = #{mnc}</if>" +
            "AND SubAccount = #{subAccount} " +
            "<if test='enabled != null'>AND Enabled = #{enabled}</if>" +
            "</where>" +
            "ORDER BY Priority ASC, " +
            "CASE WHEN SubAccount IS NOT NULL AND SubAccount != '' AND SubAccount != '*' THEN 1 ELSE 0 END DESC, " +
            "CASE WHEN MNC IS NOT NULL AND MNC != '' AND MNC != '*' THEN 1 ELSE 0 END DESC, " +
            "CASE WHEN MCC IS NOT NULL AND MCC != '' AND MCC != '*' THEN 1 ELSE 0 END DESC, " +
            "UNID ASC " +
            "LIMIT #{offset}, #{limit}" +
            "</script>")
    List<ApiContentRewrite> selectPage(@Param("offset") int offset,
                                       @Param("limit") int limit,
                                       @Param("ruleName") String ruleName,
                                       @Param("mcc") String mcc,
                                       @Param("mnc") String mnc,
                                       @Param("subAccount") String subAccount,
                                       @Param("enabled") Integer enabled);

    @Select("<script>" +
            "SELECT COUNT(*) FROM tbl_APIContentRewrite " +
            "<where>" +
            "<if test='ruleName != null and ruleName != \"\"'>AND RuleName LIKE CONCAT('%', #{ruleName}, '%')</if>" +
            "<if test='mcc != null and mcc != \"\"'>AND MCC = #{mcc}</if>" +
            "<if test='mnc != null and mnc != \"\"'>AND MNC = #{mnc}</if>" +
            "AND SubAccount = #{subAccount} " +
            "<if test='enabled != null'>AND Enabled = #{enabled}</if>" +
            "</where>" +
            "</script>")
    long countForPage(@Param("ruleName") String ruleName,
                      @Param("mcc") String mcc,
                      @Param("mnc") String mnc,
                      @Param("subAccount") String subAccount,
                      @Param("enabled") Integer enabled);

    @Select("SELECT SubAccount FROM tbl_APIContentRewrite WHERE UNID = #{unid} FOR UPDATE")
    String selectOwnerForUpdate(@Param("unid") Integer unid);

    @Results(id = "apiContentRewriteSaveResultMap", value = {
            @Result(column = "UNID", property = "unid"),
            @Result(column = "ErrCode", property = "errCode"),
            @Result(column = "ErrInfo", property = "errInfo")
    })
    @Options(statementType = StatementType.CALLABLE)
    @Select("{CALL prc_AddAPIContentRewrite(" +
            "#{rule.ruleName}, #{rule.mcc}, #{rule.mnc}, #{rule.subAccount}, " +
            "#{rule.matchPattern}, #{rule.replaceTemplate}, #{rule.priority}, " +
            "#{rule.ignoreCase}, #{rule.globalReplace}, #{rule.stopOnMatch}, " +
            "#{rule.randNoRepeat}, #{rule.enabled}, #{rule.remark})}")
    ApiContentRewriteSaveResult add(@Param("rule") ApiContentRewrite rule);

    @Options(statementType = StatementType.CALLABLE)
    @ResultMap("apiContentRewriteSaveResultMap")
    @Select("{CALL prc_UpdAPIContentRewrite(" +
            "#{rule.unid}, #{rule.ruleName}, #{rule.mcc}, #{rule.mnc}, #{rule.subAccount}, " +
            "#{rule.matchPattern}, #{rule.replaceTemplate}, #{rule.priority}, " +
            "#{rule.ignoreCase}, #{rule.globalReplace}, #{rule.stopOnMatch}, " +
            "#{rule.randNoRepeat}, #{rule.enabled}, #{rule.remark})}")
    ApiContentRewriteSaveResult update(@Param("rule") ApiContentRewrite rule);

    @Options(statementType = StatementType.CALLABLE)
    @ResultMap("apiContentRewriteSaveResultMap")
    @Select("{CALL prc_DelAPIContentRewrite(#{unid})}")
    ApiContentRewriteSaveResult delete(@Param("unid") Integer unid);
}
