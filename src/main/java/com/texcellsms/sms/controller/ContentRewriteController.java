package com.texcellsms.sms.controller;

import com.texcellsms.sms.constant.RequestHeaderConstants;
import com.texcellsms.sms.model.DTO.ApiContentRewriteSaveResult;
import com.texcellsms.sms.model.entity.ApiContentRewrite;
import com.texcellsms.sms.service.ApiContentRewriteService;
import com.texcellsms.sms.service.TokenService;
import com.texcellsms.sms.util.Constants;
import com.texcellsms.sms.util.Result;
import com.texcellsms.sms.util.SMSResponseMessage;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

import javax.servlet.http.HttpServletResponse;
import java.util.Map;

@RestController
@RequestMapping("/api/content-rewrite")
@RequiredArgsConstructor
public class ContentRewriteController {

    private final ApiContentRewriteService apiContentRewriteService;

    @GetMapping({"", "/", "/list"})
    public Result<Map<String, Object>> list(
            @RequestHeader(RequestHeaderConstants.AUTHORIZATION) String authorization,
            @RequestParam(required = false) String ruleName,
            @RequestParam(value = "RuleName", required = false) String ruleNameUpper,
            @RequestParam(required = false) String mcc,
            @RequestParam(value = "MCC", required = false) String mccUpper,
            @RequestParam(required = false) String mnc,
            @RequestParam(value = "MNC", required = false) String mncUpper,
            @RequestParam(required = false) Integer enabled,
            @RequestParam(value = "Enabled", required = false) Integer enabledUpper,
            @RequestParam(required = false) Integer onlyEnabled,
            @RequestParam(value = "OnlyEnabled", required = false) Integer onlyEnabledUpper,
            @RequestParam(required = false) Integer pageNum,
            @RequestParam(required = false) Integer pageSize,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size,
            HttpServletResponse response) {
        refreshToken(authorization, response);
        String account = TokenService.getUsername(authorization);
        if (account == null || account.trim().isEmpty()) {
            return Result.fail(SMSResponseMessage.AUTHORIZATIONERROR);
        }
        try {
            return Result.success(apiContentRewriteService.query(
                    account,
                    firstNonNull(ruleName, ruleNameUpper),
                    firstNonNull(mcc, mccUpper),
                    firstNonNull(mnc, mncUpper),
                    firstNonNull(enabled, enabledUpper),
                    firstNonNull(onlyEnabled, onlyEnabledUpper),
                    firstNonNull(pageNum, page),
                    firstNonNull(pageSize, size)));
        } catch (IllegalArgumentException e) {
            return Result.paramError(e.getMessage());
        }
    }

    @PostMapping({"", "/", "/add"})
    public Result<ApiContentRewriteSaveResult> add(
            @RequestHeader(RequestHeaderConstants.AUTHORIZATION) String authorization,
            @RequestBody ApiContentRewrite rule,
            HttpServletResponse response) {
        refreshToken(authorization, response);
        String account = TokenService.getUsername(authorization);
        if (account == null || account.trim().isEmpty()) {
            return Result.fail(SMSResponseMessage.AUTHORIZATIONERROR);
        }
        try {
            return toResult(apiContentRewriteService.add(account, rule));
        } catch (IllegalArgumentException e) {
            return Result.paramError(e.getMessage());
        }
    }

    @PutMapping({"/{unid}", "/update/{unid}"})
    public Result<ApiContentRewriteSaveResult> updateByPut(
            @RequestHeader(RequestHeaderConstants.AUTHORIZATION) String authorization,
            @PathVariable Integer unid,
            @RequestBody ApiContentRewrite rule,
            HttpServletResponse response) {
        refreshToken(authorization, response);
        return doUpdate(authorization, unid, rule);
    }

    @RequestMapping(value = "/update", method = {RequestMethod.POST, RequestMethod.PUT})
    public Result<ApiContentRewriteSaveResult> updateByPost(
            @RequestHeader(RequestHeaderConstants.AUTHORIZATION) String authorization,
            @RequestBody ApiContentRewrite rule,
            HttpServletResponse response) {
        refreshToken(authorization, response);
        return doUpdate(authorization, null, rule);
    }

    @DeleteMapping({"/{unid}", "/delete/{unid}"})
    public Result<ApiContentRewriteSaveResult> delete(
            @RequestHeader(RequestHeaderConstants.AUTHORIZATION) String authorization,
            @PathVariable Integer unid,
            HttpServletResponse response) {
        refreshToken(authorization, response);
        String account = TokenService.getUsername(authorization);
        if (account == null || account.trim().isEmpty()) {
            return Result.fail(SMSResponseMessage.AUTHORIZATIONERROR);
        }
        try {
            return toResult(apiContentRewriteService.delete(account, unid));
        } catch (IllegalArgumentException e) {
            return Result.paramError(e.getMessage());
        }
    }

    private Result<ApiContentRewriteSaveResult> doUpdate(String authorization, Integer unid, ApiContentRewrite rule) {
        String account = TokenService.getUsername(authorization);
        if (account == null || account.trim().isEmpty()) {
            return Result.fail(SMSResponseMessage.AUTHORIZATIONERROR);
        }
        try {
            if (rule == null) {
                rule = new ApiContentRewrite();
            }
            if (unid != null) {
                rule.setUnid(unid);
            }
            return toResult(apiContentRewriteService.update(account, rule));
        } catch (IllegalArgumentException e) {
            return Result.paramError(e.getMessage());
        }
    }

    private Result<ApiContentRewriteSaveResult> toResult(ApiContentRewriteSaveResult saveResult) {
        if (saveResult == null) {
            return Result.fail("stored procedure returned empty result");
        }
        Integer errCode = saveResult.getErrCode();
        if (errCode != null && errCode != 0) {
            return new Result<>(400, saveResult.getErrInfo(), saveResult);
        }
        return Result.success(saveResult);
    }

    private void refreshToken(String authorization, HttpServletResponse response) {
        String newToken = TokenService.refreshToken(authorization);
        if (newToken != null) {
            response.setHeader(Constants.HEADER, newToken);
        }
    }

    private <T> T firstNonNull(T first, T second) {
        return first != null ? first : second;
    }
}
