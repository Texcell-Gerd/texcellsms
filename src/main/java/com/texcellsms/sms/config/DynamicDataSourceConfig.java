package com.texcellsms.sms.config;

import com.texcellsms.sms.interceptor.DynamicDataSourceInterceptor;
import org.apache.ibatis.session.SqlSessionFactory;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.datasource.lookup.AbstractRoutingDataSource;

import com.zaxxer.hikari.HikariDataSource;

import javax.sql.DataSource;
import java.util.HashMap;
import java.util.Map;

/**
 * 目前2个物理数据源 + 多库访问
 */
@Configuration
@MapperScan(basePackages = "com.texcellsms.sms.mapper")
public class DynamicDataSourceConfig {

    // ========== 1. 物理数据源1：prepay（192.168.120.248，包含CDR_DR/MngDB库） ==========
    @Bean(name = "prepayDataSource")
    @Primary
    public DataSource prepayDataSource() {
        HikariDataSource ds = new HikariDataSource();
        ds.setDriverClassName("com.mysql.cj.jdbc.Driver");
        // 连接时不指定具体库，后续SQL中用「库名.表名」访问
        ds.setJdbcUrl("jdbc:mysql://192.168.120.248:14333/?useUnicode=true&characterEncoding=utf8&serverTimezone=UTC&allowPublicKeyRetrieval=true&useSSL=false");
        ds.setUsername("Harry");
        ds.setPassword("z147258"); // 你的实际密码
        ds.setMaximumPoolSize(10);
        ds.setMinimumIdle(2);
        ds.setConnectionTimeout(30000);
        ds.setIdleTimeout(600000);
        return ds;
    }

    // ========== 2. 物理数据源2：eccom（192.168.112.248，包含CDR_DR/MngDB库） ==========
    @Bean(name = "eccomDataSource")
    public DataSource eccomDataSource() {
        HikariDataSource ds = new HikariDataSource();
        ds.setDriverClassName("com.mysql.cj.jdbc.Driver");
        // 连接时不指定具体库，后续SQL中用「库名.表名」访问
        ds.setJdbcUrl("jdbc:mysql://192.168.112.248:14333/?useUnicode=true&characterEncoding=utf8&serverTimezone=UTC&allowPublicKeyRetrieval=true&useSSL=false");
        ds.setUsername("sms");
        ds.setPassword("sms"); // 你的实际密码
        ds.setMaximumPoolSize(10);
        ds.setMinimumIdle(2);
        ds.setConnectionTimeout(30000);
        ds.setIdleTimeout(600000);
        return ds;
    }

    // ========== 3. 动态路由数据源（只切换物理数据源） ==========
    @Bean(name = "routingDataSource")
    public AbstractRoutingDataSource routingDataSource(
            DataSource prepayDataSource,
            DataSource eccomDataSource) {
        AbstractRoutingDataSource routingDataSource = new AbstractRoutingDataSource() {
            @Override
            protected Object determineCurrentLookupKey() {
                // 从拦截器获取当前物理数据源key，兜底返回prepay
                String key = DynamicDataSourceInterceptor.getCurrentDataSource();
                return key == null ? DynamicDataSourceInterceptor.DS_PREPAY : key;
            }
        };

        // 绑定物理数据源映射（简化为2个）
        Map<Object, Object> dataSourceMap = new HashMap<>();
        dataSourceMap.put(DynamicDataSourceInterceptor.DS_ECCOM, eccomDataSource);
        dataSourceMap.put(DynamicDataSourceInterceptor.DS_PREPAY, prepayDataSource);

        routingDataSource.setTargetDataSources(dataSourceMap);
        routingDataSource.setDefaultTargetDataSource(prepayDataSource); // 默认物理数据源
        routingDataSource.afterPropertiesSet();

        return routingDataSource;
    }

    // ========== 4. MyBatis配置（绑定路由数据源） ==========
    @Bean
    public SqlSessionFactory sqlSessionFactory(AbstractRoutingDataSource routingDataSource) throws Exception {
        SqlSessionFactoryBean sessionFactoryBean = new SqlSessionFactoryBean();
        sessionFactoryBean.setDataSource(routingDataSource);
        sessionFactoryBean.setTypeAliasesPackage("com.texcellsms.sms.model.entity");

        org.apache.ibatis.session.Configuration configuration = new org.apache.ibatis.session.Configuration();
        configuration.setMapUnderscoreToCamelCase(true);
        sessionFactoryBean.setConfiguration(configuration);

        return sessionFactoryBean.getObject();
    }

    @Bean
    public SqlSessionTemplate sqlSessionTemplate(SqlSessionFactory sqlSessionFactory) {
        return new SqlSessionTemplate(sqlSessionFactory);
    }
}