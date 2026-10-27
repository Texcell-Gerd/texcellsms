package com.texcellsms.sms.controller;

import com.texcellsms.sms.service.FeatureService;
import com.texcellsms.sms.service.TokenService;
import com.texcellsms.sms.util.AjaxResult;
import com.texcellsms.sms.util.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/eccom/feature")
public class FeatureController {

    @Autowired
    private FeatureService featureService;

    /**
     * 客户登录后调用，返回可用模块列表
     */
    @GetMapping("/my-modules")
    public AjaxResult myModules() {
        String subAccount = TokenService.getUsername();
        if (StringUtils.isEmpty(subAccount)) {
            return AjaxResult.error("Not logged in");
        }
        return AjaxResult.success(featureService.getAuthorizedModules(subAccount));
    }
}