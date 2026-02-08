package com.texcellsms.sms.config;

import com.texcellsms.sms.interceptor.DynamicDataSourceInterceptor;
import org.apache.ibatis.session.LocalCacheScope;
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

@Configuration
@MapperScan(basePackages = "com.texcellsms.sms.mapper.prepay", sqlSessionFactoryRef = "prepaySqlSessionFactory")
@MapperScan(basePackages = "com.texcellsms.sms.mapper.eccom", sqlSessionFactoryRef = "eccomSqlSessionFactory")
@MapperScan(basePackages = "com.texcellsms.sms.mapper.example", sqlSessionFactoryRef = "exampleSqlSessionFactory")
@MapperScan(basePackages = "com.texcellsms.sms.mapper.auth", sqlSessionFactoryRef = "authSqlSessionFactory")
@MapperScan(basePackages = "com.texcellsms.sms.mapper", sqlSessionFactoryRef = "sqlSessionFactory")
public class DynamicDataSourceConfig {

    public static final String DS_PREPAY = "prepay";
    public static final String DS_ECCOM = "eccom";
    public static final String DS_EXAMPLE = "example";
    public static final String DS_AUTH = "auth";

    @Bean(name = "prepayDataSource")
    @Primary
    public DataSource prepayDataSource() {
        HikariDataSource ds = new HikariDataSource();
        ds.setDriverClassName("com.mysql.cj.jdbc.Driver");

        ds.setJdbcUrl("jdbc:mysql://192.168.120.248:14333/CDR_DB?useUnicode=true&characterEncoding=utf8&serverTimezone=UTC&allowPublicKeyRetrieval=true&useSSL=false&connectTimeout=300000&socketTimeout=600000&queryTimeout=600&rewriteBatchedStatements=true");
        ds.setUsername("Harry");
        ds.setPassword("z147258");
        ds.setMaximumPoolSize(10); // 恢复原始值
        ds.setMinimumIdle(2);
        ds.setConnectionTimeout(30000);
        ds.setIdleTimeout(600000);
        ds.setLeakDetectionThreshold(60000);
        ds.setMaxLifetime(28800000);
        ds.setValidationTimeout(30000);
        ds.setConnectionTestQuery("SELECT 1");
        return ds;
    }

    @Bean(name = "eccomDataSource")
    public DataSource eccomDataSource() {
        HikariDataSource ds = new HikariDataSource();
        ds.setDriverClassName("com.mysql.cj.jdbc.Driver");
        ds.setJdbcUrl("jdbc:mysql://192.168.112.248:14333/?useUnicode=true&characterEncoding=utf8&serverTimezone=UTC&allowPublicKeyRetrieval=true&useSSL=false");
        ds.setUsername("sms");
        ds.setPassword("sms");
        ds.setMaximumPoolSize(10);
        ds.setMinimumIdle(2);
        ds.setConnectionTimeout(30000);
        ds.setIdleTimeout(600000);
        return ds;
    }

    @Bean(name = "exampleDataSource")
    public DataSource exampleDataSource() {
        HikariDataSource ds = new HikariDataSource();
        ds.setDriverClassName("com.mysql.cj.jdbc.Driver");
        ds.setJdbcUrl("jdbc:mysql://192.168.112.250:11781/example?useUnicode=true&characterEncoding=utf8&serverTimezone=UTC&allowPublicKeyRetrieval=true&useSSL=false&connectTimeout=5000&socketTimeout=30000");
        ds.setUsername("example");
        ds.setPassword("example");
        ds.setMaximumPoolSize(8);
        ds.setMinimumIdle(1);
        ds.setConnectionTimeout(30000);
        ds.setIdleTimeout(600000);
        ds.setLeakDetectionThreshold(60000);
        return ds;
    }

    @Bean(name = "authDataSource")
    public DataSource authDataSource() {
        HikariDataSource ds = new HikariDataSource();
        ds.setDriverClassName("com.mysql.cj.jdbc.Driver");
        ds.setJdbcUrl("jdbc:mysql://192.168.112.250:18801/auth?useUnicode=true&characterEncoding=utf8&serverTimezone=UTC&allowPublicKeyRetrieval=true&useSSL=false&connectTimeout=5000&socketTimeout=30000");
        ds.setUsername("auth");
        ds.setPassword("auth");
        ds.setMaximumPoolSize(8);
        ds.setMinimumIdle(1);
        ds.setConnectionTimeout(30000);
        ds.setIdleTimeout(600000);
        ds.setLeakDetectionThreshold(60000);
        return ds;
    }

    @Bean(name = "routingDataSource")
    public AbstractRoutingDataSource routingDataSource(
            DataSource prepayDataSource,
            DataSource eccomDataSource,
            DataSource exampleDataSource,
            DataSource authDataSource) {
        AbstractRoutingDataSource routingDataSource = new AbstractRoutingDataSource() {
            @Override
            protected Object determineCurrentLookupKey() {
                String key = DynamicDataSourceInterceptor.getCurrentDataSource();
                return key == null ? DS_PREPAY : key;
            }
        };

        Map<Object, Object> dataSourceMap = new HashMap<>();
        dataSourceMap.put(DS_PREPAY, prepayDataSource);
        dataSourceMap.put(DS_ECCOM, eccomDataSource);
        dataSourceMap.put(DS_EXAMPLE, exampleDataSource);
        dataSourceMap.put(DS_AUTH, authDataSource);

        routingDataSource.setTargetDataSources(dataSourceMap);
        routingDataSource.setDefaultTargetDataSource(prepayDataSource);
        routingDataSource.afterPropertiesSet();

        return routingDataSource;
    }

    @Bean(name = "prepaySqlSessionFactory")
    public SqlSessionFactory prepaySqlSessionFactory() throws Exception {
        SqlSessionFactoryBean sessionFactoryBean = new SqlSessionFactoryBean();
        sessionFactoryBean.setDataSource(prepayDataSource());
        sessionFactoryBean.setTypeAliasesPackage("com.texcellsms.sms.model.entity,com.texcellsms.sms.model.DTO");

        org.apache.ibatis.session.Configuration configuration = new org.apache.ibatis.session.Configuration();
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.setDefaultStatementTimeout(600); // 10分钟SQL超时
        configuration.setDefaultFetchSize(1000); // 与数据源的defaultFetchSize保持一致
        configuration.setLocalCacheScope(LocalCacheScope.STATEMENT);
        configuration.setCacheEnabled(false);
        configuration.setLazyLoadingEnabled(false); // 禁用延迟加载
        configuration.setAggressiveLazyLoading(false);
        configuration.setMultipleResultSetsEnabled(true);
        configuration.setUseColumnLabel(true);
        configuration.setUseGeneratedKeys(false); // 对于查询操作可禁用

        sessionFactoryBean.setConfiguration(configuration);

        return sessionFactoryBean.getObject();
    }

    @Bean(name = "eccomSqlSessionFactory")
    public SqlSessionFactory eccomSqlSessionFactory() throws Exception {
        SqlSessionFactoryBean sessionFactoryBean = new SqlSessionFactoryBean();
        sessionFactoryBean.setDataSource(eccomDataSource());
        sessionFactoryBean.setTypeAliasesPackage("com.texcellsms.sms.model.entity,com.texcellsms.sms.model.DTO");

        org.apache.ibatis.session.Configuration configuration = new org.apache.ibatis.session.Configuration();
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.setDefaultStatementTimeout(30);
        sessionFactoryBean.setConfiguration(configuration);

        return sessionFactoryBean.getObject();
    }

    @Bean(name = "exampleSqlSessionFactory")
    public SqlSessionFactory exampleSqlSessionFactory() throws Exception {
        SqlSessionFactoryBean sessionFactoryBean = new SqlSessionFactoryBean();
        sessionFactoryBean.setDataSource(exampleDataSource());
        sessionFactoryBean.setTypeAliasesPackage("com.texcellsms.sms.model.entity,com.texcellsms.sms.model.DTO");

        org.apache.ibatis.session.Configuration configuration = new org.apache.ibatis.session.Configuration();
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.setDefaultStatementTimeout(30);
        sessionFactoryBean.setConfiguration(configuration);

        return sessionFactoryBean.getObject();
    }

    @Bean(name = "authSqlSessionFactory")
    public SqlSessionFactory authSqlSessionFactory() throws Exception {
        SqlSessionFactoryBean sessionFactoryBean = new SqlSessionFactoryBean();
        sessionFactoryBean.setDataSource(authDataSource());
        sessionFactoryBean.setTypeAliasesPackage("com.texcellsms.sms.model.entity,com.texcellsms.sms.model.DTO");

        org.apache.ibatis.session.Configuration configuration = new org.apache.ibatis.session.Configuration();
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.setDefaultStatementTimeout(30);
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
        configuration.setDefaultStatementTimeout(180);
        configuration.setDefaultFetchSize(500);
        configuration.setLocalCacheScope(LocalCacheScope.STATEMENT);
        sessionFactoryBean.setConfiguration(configuration);

        return sessionFactoryBean.getObject();
    }

    @Bean(name = "prepaySqlSessionTemplate")
    public SqlSessionTemplate prepaySqlSessionTemplate() throws Exception {
        return new SqlSessionTemplate(prepaySqlSessionFactory());
    }

    @Bean(name = "eccomSqlSessionTemplate")
    public SqlSessionTemplate eccomSqlSessionTemplate() throws Exception {
        return new SqlSessionTemplate(eccomSqlSessionFactory());
    }

    @Bean(name = "exampleSqlSessionTemplate")
    public SqlSessionTemplate exampleSqlSessionTemplate() throws Exception {
        return new SqlSessionTemplate(exampleSqlSessionFactory());
    }

    @Bean(name = "authSqlSessionTemplate")
    public SqlSessionTemplate authSqlSessionTemplate() throws Exception {
        return new SqlSessionTemplate(authSqlSessionFactory());
    }

    @Bean
    public SqlSessionTemplate sqlSessionTemplate(SqlSessionFactory sqlSessionFactory) {
        return new SqlSessionTemplate(sqlSessionFactory);
    }
}