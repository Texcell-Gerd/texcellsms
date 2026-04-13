package com.texcellsms.sms.config;

import com.zaxxer.hikari.HikariDataSource;
import org.apache.ibatis.session.LocalCacheScope;
import org.apache.ibatis.session.SqlSessionFactory;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.datasource.lookup.AbstractRoutingDataSource;

import javax.sql.DataSource;
import java.util.HashMap;
import java.util.Map;

@Configuration
@MapperScan(
        basePackages = "com.texcellsms.sms.mapper.eccom",
        sqlSessionFactoryRef = "eccomSqlSessionFactory",
        nameGenerator = ModulePrefixBeanNameGenerator.class
)
@MapperScan(
        basePackages = "com.texcellsms.sms.mapper.webportal",
        sqlSessionFactoryRef = "webPortalSqlSessionFactory",
        nameGenerator = ModulePrefixBeanNameGenerator.class
)
public class DynamicDataSourceConfig {

    public static final String DS_ECCOM = "eccom";
    public static final String DS_WEB_PORTAL = "webPortal";

    private static final ThreadLocal<String> DATA_SOURCE_CONTEXT = new ThreadLocal<>();

    public static String getCurrentDataSource() {
        String key = DATA_SOURCE_CONTEXT.get();
        return key == null ? DS_ECCOM : key;
    }

    public static void setCurrentDataSource(String dataSourceKey) {
        if (dataSourceKey != null && !dataSourceKey.isEmpty()) {
            DATA_SOURCE_CONTEXT.set(dataSourceKey);
        }
    }

    public static void clearCurrentDataSource() {
        DATA_SOURCE_CONTEXT.remove();
    }

//    @Bean(name = "eccomDataSource")
//    @Primary
//    public DataSource eccomDataSource() {
//        HikariDataSource ds = new HikariDataSource();
//        ds.setDriverClassName("com.mysql.cj.jdbc.Driver");
//        ds.setJdbcUrl("jdbc:mysql://192.168.112.248:14333/CDR_DB?useUnicode=true&characterEncoding=utf8&serverTimezone=UTC&allowPublicKeyRetrieval=true&useSSL=false");
//        ds.setUsername("sms");
//        ds.setPassword("sms");
//        ds.setMaximumPoolSize(10);
//        ds.setMinimumIdle(2);
//        ds.setConnectionTimeout(600000);
//        ds.setIdleTimeout(600000);
//        ds.setLeakDetectionThreshold(300000);
//        ds.setMaxLifetime(28800000);
//        ds.setValidationTimeout(30000);
//        ds.setConnectionTestQuery("SELECT 1");
//        return ds;
//    }

    //测试环境数据源
    @Bean(name = "eccomDataSource")
    @Primary
    public DataSource eccomDataSource() {
        HikariDataSource ds = new HikariDataSource();
        ds.setDriverClassName("com.mysql.cj.jdbc.Driver");
        ds.setJdbcUrl("jdbc:mysql://192.168.128.248:14333/CDR_DB?useUnicode=true&characterEncoding=utf8&serverTimezone=UTC&allowPublicKeyRetrieval=true&useSSL=false");
        ds.setUsername("root");
        ds.setPassword("9c98eCFD84F299F76F6D4252DE111484(*&^%$");
        ds.setMaximumPoolSize(10);
        ds.setMinimumIdle(2);
        ds.setConnectionTimeout(600000);
        ds.setIdleTimeout(600000);
        ds.setLeakDetectionThreshold(300000);
        ds.setMaxLifetime(28800000);
        ds.setValidationTimeout(30000);
        ds.setConnectionTestQuery("SELECT 1");
        return ds;
    }


    //测试环境数据源
    @Bean(name = "webPortalDataSource")
    public DataSource webPortalDataSource() {
        HikariDataSource ds = new HikariDataSource();
        ds.setDriverClassName("com.mysql.cj.jdbc.Driver");
        ds.setJdbcUrl("jdbc:mysql://192.168.128.247:14333/WEBPortal?useUnicode=true&characterEncoding=utf8&zeroDateTimeBehavior=convertToNull&useSSL=false&serverTimezone=Asia/Shanghai");
        ds.setUsername("root");
        ds.setPassword("9c98eCFD84F299F76F6D4252DE111484(*&^%$");
        ds.setMaximumPoolSize(10);
        ds.setMinimumIdle(2);
        ds.setConnectionTimeout(600000);
        ds.setIdleTimeout(600000);
        ds.setLeakDetectionThreshold(300000);
        ds.setMaxLifetime(28800000);
        ds.setValidationTimeout(30000);
        ds.setConnectionTestQuery("SELECT 1");
        return ds;

//    @Bean(name = "webPortalDataSource")
//    public DataSource webPortalDataSource() {
//        HikariDataSource ds = new HikariDataSource();
//        ds.setDriverClassName("com.mysql.cj.jdbc.Driver");
//        ds.setJdbcUrl("jdbc:mysql://192.168.112.229:14333/WEBPortal?useUnicode=true&characterEncoding=utf8&serverTimezone=UTC&allowPublicKeyRetrieval=true&useSSL=false");
//        ds.setUsername("sms");
//        ds.setPassword("sms");
//        ds.setMaximumPoolSize(10);
//        ds.setMinimumIdle(2);
//        ds.setConnectionTimeout(600000);
//        ds.setIdleTimeout(600000);
//        ds.setLeakDetectionThreshold(300000);
//        ds.setMaxLifetime(28800000);
//        ds.setValidationTimeout(30000);
//        ds.setConnectionTestQuery("SELECT 1");
//        return ds;
    }

    @Bean(name = "routingDataSource")
    public AbstractRoutingDataSource routingDataSource(
            DataSource eccomDataSource,
            DataSource webPortalDataSource) {
        AbstractRoutingDataSource routingDataSource = new AbstractRoutingDataSource() {
            @Override
            protected Object determineCurrentLookupKey() {
                return getCurrentDataSource();
            }
        };

        Map<Object, Object> dataSourceMap = new HashMap<>();
        dataSourceMap.put(DS_ECCOM, eccomDataSource);
        dataSourceMap.put(DS_WEB_PORTAL, webPortalDataSource);

        routingDataSource.setTargetDataSources(dataSourceMap);
        routingDataSource.setDefaultTargetDataSource(eccomDataSource);
        routingDataSource.afterPropertiesSet();

        return routingDataSource;
    }

    @Bean(name = "eccomSqlSessionFactory")
    @Primary
    public SqlSessionFactory eccomSqlSessionFactory() throws Exception {
        SqlSessionFactoryBean sessionFactoryBean = new SqlSessionFactoryBean();
        sessionFactoryBean.setDataSource(eccomDataSource());
        sessionFactoryBean.setTypeAliasesPackage("com.texcellsms.sms.model.entity,com.texcellsms.sms.model.DTO");

        org.apache.ibatis.session.Configuration configuration = new org.apache.ibatis.session.Configuration();
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.setDefaultStatementTimeout(600);
        configuration.setDefaultFetchSize(1000);
        configuration.setLocalCacheScope(LocalCacheScope.STATEMENT);
        configuration.setCacheEnabled(false);
        sessionFactoryBean.setConfiguration(configuration);

        return sessionFactoryBean.getObject();
    }

    @Bean(name = "webPortalSqlSessionFactory")
    public SqlSessionFactory webPortalSqlSessionFactory() throws Exception {
        SqlSessionFactoryBean sessionFactoryBean = new SqlSessionFactoryBean();
        sessionFactoryBean.setDataSource(webPortalDataSource());
        sessionFactoryBean.setTypeAliasesPackage("com.texcellsms.sms.model.entity,com.texcellsms.sms.model.DTO");

        org.apache.ibatis.session.Configuration configuration = new org.apache.ibatis.session.Configuration();
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.setDefaultStatementTimeout(600);
        configuration.setDefaultFetchSize(1000);
        configuration.setLocalCacheScope(LocalCacheScope.STATEMENT);
        configuration.setCacheEnabled(false);
        sessionFactoryBean.setConfiguration(configuration);

        return sessionFactoryBean.getObject();
    }

    @Bean
    public SqlSessionFactory sqlSessionFactory(AbstractRoutingDataSource routingDataSource) throws Exception {
        SqlSessionFactoryBean sessionFactoryBean = new SqlSessionFactoryBean();
        sessionFactoryBean.setDataSource(routingDataSource);
        sessionFactoryBean.setTypeAliasesPackage("com.texcellsms.sms.model.entity");

        org.apache.ibatis.session.Configuration configuration = new org.apache.ibatis.session.Configuration();
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.setDefaultStatementTimeout(600);
        configuration.setDefaultFetchSize(1000);
        configuration.setLocalCacheScope(LocalCacheScope.STATEMENT);
        configuration.setCacheEnabled(false);
        sessionFactoryBean.setConfiguration(configuration);

        return sessionFactoryBean.getObject();
    }

    @Bean(name = "eccomSqlSessionTemplate")
    @Primary
    public SqlSessionTemplate eccomSqlSessionTemplate() throws Exception {
        return new SqlSessionTemplate(eccomSqlSessionFactory());
    }

    @Bean(name = "webPortalSqlSessionTemplate")
    public SqlSessionTemplate webPortalSqlSessionTemplate() throws Exception {
        return new SqlSessionTemplate(webPortalSqlSessionFactory());
    }

    @Bean
    public SqlSessionTemplate sqlSessionTemplate(SqlSessionFactory sqlSessionFactory) {
        return new SqlSessionTemplate(sqlSessionFactory);
    }
}