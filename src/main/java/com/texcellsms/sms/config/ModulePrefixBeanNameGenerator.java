package com.texcellsms.sms.config;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.beans.factory.support.BeanNameGenerator;
import org.springframework.util.StringUtils;
public class ModulePrefixBeanNameGenerator implements BeanNameGenerator {
    @Override
    public String generateBeanName (BeanDefinition definition, BeanDefinitionRegistry registry) {
        String beanClassName = definition.getBeanClassName ();
        if (!StringUtils.hasText (beanClassName)) {
// 完全兼容的兜底方案：直接生成默认的小写类名
            return "defaultMapper";
        }
// 提取接口简单名称（如 SmsQueueMapper）
        String simpleName = beanClassName.substring (beanClassName.lastIndexOf (".") + 1);
// 首字母小写
        String lowerSimpleName = Character.toLowerCase (simpleName.charAt (0)) + simpleName.substring (1);
// 根据包路径添加模块前缀
        if (beanClassName.contains (".mapper.prepay.")) {
            return "prepay_" + lowerSimpleName;
        } else if (beanClassName.contains (".mapper.eccom.")) {
            return "eccom_" + lowerSimpleName;
        } else if (beanClassName.contains (".mapper.example.")) {
            return "example_" + lowerSimpleName;
        } else if (beanClassName.contains (".mapper.auth.")) {
            return "auth_" + lowerSimpleName;
        } else {
// 兜底：无匹配包则用原生小写名称
            return lowerSimpleName;
        }
    }
}