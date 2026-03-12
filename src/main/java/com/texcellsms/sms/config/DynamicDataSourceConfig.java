package com.texcellsms.sms.config;

import com.texcellsms.sms.interceptor.DynamicDataSourceInterceptor;
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

/**
 * 动态数据源配置类：修复Mapper Bean名称冲突，为不同模块Mapper添加专属前缀
 */
@Configuration
// 关键：指定自定义名称生成器，每个模块的Mapper扫描都用这个规则
@MapperScan(
        basePackages = "com.texcellsms.sms.mapper.prepay",
        sqlSessionFactoryRef = "prepaySqlSessionFactory",
        nameGenerator = ModulePrefixBeanNameGenerator.class // 自定义名称生成器
)
@MapperScan(
        basePackages = "com.texcellsms.sms.mapper.eccom",
        sqlSessionFactoryRef = "eccomSqlSessionFactory",
        nameGenerator = ModulePrefixBeanNameGenerator.class
)
// 新增WEBPortal的Mapper扫描
@MapperScan(
        basePackages = "com.texcellsms.sms.mapper.webportal", // 建议新增专属包
        sqlSessionFactoryRef = "webPortalSqlSessionFactory",
        nameGenerator = ModulePrefixBeanNameGenerator.class
)
public class DynamicDataSourceConfig {

    public static final String DS_PREPAY = "prepay";
    public static final String DS_ECCOM = "eccom";
    public static final String DS_WEB_PORTAL = "webPortal"; // 新增WEBPortal数据源标识

    @Bean(name = "prepayDataSource")
    @Primary
    public DataSource prepayDataSource() {
        HikariDataSource ds = new HikariDataSource();
        ds.setDriverClassName("com.mysql.cj.jdbc.Driver");
        ds.setJdbcUrl("jdbc:mysql://192.168.120.248:14333/CDR_DB?useUnicode=true&characterEncoding=utf8&serverTimezone=UTC&allowPublicKeyRetrieval=true&useSSL=false&connectTimeout=30000&socketTimeout=60000&rewriteBatchedStatements=true");
        ds.setUsername("Harry");
        ds.setPassword("z147258");
        ds.setMaximumPoolSize(10);
        ds.setMinimumIdle(2);
        ds.setConnectionTimeout(30000);
        ds.setIdleTimeout(600000);
        ds.setLeakDetectionThreshold(300000);
        ds.setMaxLifetime(28800000);
        ds.setValidationTimeout(30000);
        ds.setConnectionTestQuery("SELECT 1");
        return ds;
    }

    @Bean(name = "eccomDataSource")
    public DataSource eccomDataSource() {
        HikariDataSource ds = new HikariDataSource();
        ds.setDriverClassName("com.mysql.cj.jdbc.Driver");
        ds.setJdbcUrl("jdbc:mysql://192.168.112.248:14333/CDR_DB?useUnicode=true&characterEncoding=utf8&serverTimezone=UTC&allowPublicKeyRetrieval=true&useSSL=false");
        ds.setUsername("sms");
        ds.setPassword("sms");
        ds.setMaximumPoolSize(10);
        ds.setMinimumIdle(2);
        ds.setConnectionTimeout(30000);
        ds.setIdleTimeout(600000);
        ds.setLeakDetectionThreshold(300000);
        ds.setMaxLifetime(28800000);
        ds.setValidationTimeout(30000);
        ds.setConnectionTestQuery("SELECT 1");
        return ds;
    }

    // 新增WEBPortal数据源配置
    @Bean(name = "webPortalDataSource")
    public DataSource webPortalDataSource() {
        HikariDataSource ds = new HikariDataSource();
        ds.setDriverClassName("com.mysql.cj.jdbc.Driver"); // 如果是SQL Server需改为com.microsoft.sqlserver.jdbc.SQLServerDriver
        // 根据数据库类型调整URL：MySQL格式 jdbc:mysql://IP:端口/库名 | SQL Server格式 jdbc:sqlserver://IP:端口;DatabaseName=库名
        ds.setJdbcUrl("jdbc:mysql://192.168.128.247:14333/WEBPortal?useUnicode=true&characterEncoding=utf8&serverTimezone=UTC&allowPublicKeyRetrieval=true&useSSL=false");
        ds.setUsername("root");
        ds.setPassword("9c98eCFD84F299F76F6D4252DE111484(*&^%$");
        ds.setMaximumPoolSize(10);
        ds.setMinimumIdle(2);
        ds.setConnectionTimeout(30000);
        ds.setIdleTimeout(600000);
        ds.setLeakDetectionThreshold(300000);
        ds.setMaxLifetime(28800000);
        ds.setValidationTimeout(30000);
        ds.setConnectionTestQuery("SELECT 1");
        return ds;
    }

    @Bean(name = "routingDataSource")
    public AbstractRoutingDataSource routingDataSource(
            DataSource prepayDataSource,
            DataSource eccomDataSource,
            DataSource webPortalDataSource) {
        AbstractRoutingDataSource routingDataSource = new AbstractRoutingDataSource() {
            @Override
            protected Object determineCurrentLookupKey() {
                // 关键修改：从新的 DataSourceInterceptor 获取数据源key
                String key = com.texcellsms.sms.constant.DataSourceInterceptor.getCurrentDataSource();
                // 新增日志，方便调试
                System.out.println("当前路由数据源key：" + key);
                return key == null ? DS_PREPAY : key;
            }
        };

        Map<Object, Object> dataSourceMap = new HashMap<>();
        dataSourceMap.put(DS_PREPAY, prepayDataSource);
        dataSourceMap.put(DS_ECCOM, eccomDataSource);
        dataSourceMap.put(DS_WEB_PORTAL, webPortalDataSource);

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
        configuration.setDefaultStatementTimeout(300);
        configuration.setDefaultFetchSize(1000);
        configuration.setLocalCacheScope(LocalCacheScope.STATEMENT);
        configuration.setCacheEnabled(false);
        configuration.setLazyLoadingEnabled(false);
        configuration.setAggressiveLazyLoading(false);
        configuration.setMultipleResultSetsEnabled(true);
        configuration.setUseColumnLabel(true);
        configuration.setUseGeneratedKeys(false);

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
        configuration.setLocalCacheScope(LocalCacheScope.STATEMENT);
        configuration.setCacheEnabled(false);
        sessionFactoryBean.setConfiguration(configuration);

        return sessionFactoryBean.getObject();
    }

    // 新增WEBPortal的SqlSessionFactory
    @Bean(name = "webPortalSqlSessionFactory")
    public SqlSessionFactory webPortalSqlSessionFactory() throws Exception {
        SqlSessionFactoryBean sessionFactoryBean = new SqlSessionFactoryBean();
        sessionFactoryBean.setDataSource(webPortalDataSource());
        sessionFactoryBean.setTypeAliasesPackage("com.texcellsms.sms.model.entity,com.texcellsms.sms.model.DTO");

        org.apache.ibatis.session.Configuration configuration = new org.apache.ibatis.session.Configuration();
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.setDefaultStatementTimeout(30);
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
        configuration.setDefaultStatementTimeout(180);
        configuration.setDefaultFetchSize(500);
        configuration.setLocalCacheScope(LocalCacheScope.STATEMENT);
        configuration.setCacheEnabled(false);
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

    // 新增WEBPortal的SqlSessionTemplate
    @Bean(name = "webPortalSqlSessionTemplate")
    public SqlSessionTemplate webPortalSqlSessionTemplate() throws Exception {
        return new SqlSessionTemplate(webPortalSqlSessionFactory());
    }

    @Bean
    public SqlSessionTemplate sqlSessionTemplate(SqlSessionFactory sqlSessionFactory) {
        return new SqlSessionTemplate(sqlSessionFactory);
    }
}