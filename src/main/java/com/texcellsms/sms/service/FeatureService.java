package com.texcellsms.sms.service;

import com.texcellsms.sms.mapper.webportal.CustomerFeatureAuthMapper;
import com.texcellsms.sms.mapper.webportal.FeatureModuleMapper;
import com.texcellsms.sms.model.entity.CustomerFeatureAuth;
import com.texcellsms.sms.model.entity.FeatureModule;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

@Service
public class FeatureService {

    @Autowired
    private FeatureModuleMapper featureModuleMapper;

    @Autowired
    private CustomerFeatureAuthMapper customerFeatureAuthMapper;

    /** 获取某客户授权的模块列表 */
    public List<FeatureModule> getAuthorizedModules(String subAccount) {
        List<FeatureModule> all = featureModuleMapper.selectEnabledAll();
        List<String> authedCodes = customerFeatureAuthMapper.selectEnabledCodesByAccount(subAccount);

        // 定义所有客户默认拥有的公共模块（只要全局开了，客户就有）
        List<String> publicModules = Arrays.asList("advanced_task_settings", "some_public_page");

        return all.stream()
                .filter(m -> publicModules.contains(m.getModuleCode()) || authedCodes.contains(m.getModuleCode()))
                .collect(Collectors.toList());
    }

    public boolean hasAuth(String subAccount, String moduleCode) {
        if (subAccount == null || moduleCode == null) return false;
        // 公共模块直接返回 true
        List<String> publicModules = Arrays.asList("advanced_task_settings", "some_public_page");
        if (publicModules.contains(moduleCode)) return true;

        return customerFeatureAuthMapper.checkAuth(subAccount, moduleCode) > 0;
    }

    /** ==== 管理端接口 ==== */

    public List<FeatureModule> listAllModules() {
        return featureModuleMapper.selectAll();
    }

    public FeatureModule saveModule(FeatureModule module) {
        if (module.getId() == null) {
            featureModuleMapper.insert(module);
        } else {
            featureModuleMapper.update(module);
        }
        return module;
    }

    public void toggleModule(Long id, Integer enabled) {
        featureModuleMapper.toggleEnabled(id, enabled);
    }

    public List<CustomerFeatureAuth> listAuthByModule(String moduleCode) {
        return customerFeatureAuthMapper.selectByModuleCode(moduleCode);
    }

    public void saveAuth(String subAccount, String moduleCode, Integer enabled) {
        CustomerFeatureAuth auth = new CustomerFeatureAuth();
        auth.setSubAccount(subAccount);
        auth.setModuleCode(moduleCode);
        auth.setEnabled(enabled);
        customerFeatureAuthMapper.upsert(auth);
    }

    public void removeAuth(String subAccount, String moduleCode) {
        customerFeatureAuthMapper.deleteAuth(subAccount, moduleCode);
    }

    public void deleteModule(Long id) {
        featureModuleMapper.deleteById(id);
    }
}