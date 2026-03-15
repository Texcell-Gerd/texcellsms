package com.texcellsms.sms.config;

import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.beans.factory.support.BeanNameGenerator;
import org.springframework.util.StringUtils;

public class ModulePrefixBeanNameGenerator implements BeanNameGenerator {
    @Override
    public String generateBeanName(BeanDefinition definition, BeanDefinitionRegistry registry) {
        String beanClassName = definition.getBeanClassName();
        if (!StringUtils.hasText(beanClassName)) {
            return "defaultMapper";
        }
        String simpleName = beanClassName.substring(beanClassName.lastIndexOf(".") + 1);
        String lowerSimpleName = Character.toLowerCase(simpleName.charAt(0)) + simpleName.substring(1);

        if (beanClassName.contains(".mapper.eccom.")) {
            return "eccom_" + lowerSimpleName;
        } else if (beanClassName.contains(".mapper.webportal.")) {
            return "webPortal_" + lowerSimpleName;
        } else {
            return lowerSimpleName;
        }
    }
}