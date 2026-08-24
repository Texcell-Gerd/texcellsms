package com.texcellsms.sms.service;

import com.texcellsms.sms.mapper.webportal.IpRateLimitConfigMapper;
import com.texcellsms.sms.mapper.webportal.OpsAuditLogMapper;
import com.texcellsms.sms.model.entity.IpRateLimitConfig;
import com.texcellsms.sms.model.entity.OpsAuditLog;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import javax.servlet.http.HttpServletRequest;
import java.util.List;

@Service
public class IpRateLimitConfigService {

    private static final Logger logger = LoggerFactory.getLogger(IpRateLimitConfigService.class);

    @Resource
    private IpRateLimitConfigMapper configMapper;

    @Resource
    private OpsAuditLogMapper auditLogMapper;

    @Resource
    private HttpServletRequest request;

    /**
     * 获取所有限流配置
     */
    public List<IpRateLimitConfig> listAll() {
        return configMapper.selectAll();
    }

    /**
     * 获取所有启用的限流配置
     */
    public List<IpRateLimitConfig> listEnabled() {
        return configMapper.selectEnabled();
    }

    /**
     * 根据ID获取限流配置
     */
    public IpRateLimitConfig getById(Long id) {
        return configMapper.selectById(id);
    }

    /**
     * 根据IP获取限流配置
     */
    public IpRateLimitConfig getByIp(String ip) {
        return configMapper.selectByIp(ip);
    }

    /**
     * 新增或修改限流配置
     */
    @Transactional
    public void saveConfig(IpRateLimitConfig config, String operator) {
        // 获取当前请求IP
        String requestIp = getClientIp();

        if (config.getId() == null) {
            // ===== 新增 =====
            // 检查IP是否已存在
            IpRateLimitConfig existing = configMapper.selectByIp(config.getIp());
            if (existing != null) {
                throw new RuntimeException("IP " + config.getIp() + " 的限流配置已存在，请勿重复添加");
            }

            config.setCreateBy(operator);
            config.setUpdateBy(operator);
            configMapper.insert(config);

            logger.info("✅ {} 新增限流配置: IP={}, 限流={}/分钟, 封禁={}分钟",
                    operator, config.getIp(), config.getRateLimit(), config.getBlockDuration());

            // 记录操作日志
            OpsAuditLog log = new OpsAuditLog();
            log.setOperator(operator);
            log.setOperationType("RATE_LIMIT_CREATE");
            log.setOperationName("新增限流配置");
            log.setTarget(config.getIp());
            log.setResult(1);
            log.setResultMsg(String.format("IP: %s, 客户: %s, 限流: %d次/分钟, 封禁: %d分钟, 备注: %s",
                    config.getIp(),
                    config.getCustomerName() != null ? config.getCustomerName() : "-",
                    config.getRateLimit(),
                    config.getBlockDuration(),
                    config.getRemark() != null ? config.getRemark() : "-"));
            log.setRequestIp(requestIp);
            auditLogMapper.insert(log);

        } else {
            // ===== 修改 =====
            // 检查配置是否存在
            IpRateLimitConfig oldConfig = configMapper.selectById(config.getId());
            if (oldConfig == null) {
                throw new RuntimeException("配置不存在: id=" + config.getId());
            }

            config.setUpdateBy(operator);
            configMapper.update(config);

            logger.info("✏️ {} 修改限流配置: ID={}, IP={}, 限流={}/分钟",
                    operator, config.getId(), config.getIp(), config.getRateLimit());

            // 记录操作日志
            OpsAuditLog log = new OpsAuditLog();
            log.setOperator(operator);
            log.setOperationType("RATE_LIMIT_UPDATE");
            log.setOperationName("修改限流配置");
            log.setTarget(config.getIp());
            log.setResult(1);
            log.setResultMsg(String.format("IP: %s, 限流: %d→%d次/分钟, 封禁: %d→%d分钟",
                    config.getIp(),
                    oldConfig.getRateLimit(), config.getRateLimit(),
                    oldConfig.getBlockDuration(), config.getBlockDuration()));
            log.setRequestIp(requestIp);
            auditLogMapper.insert(log);
        }
    }

    /**
     * 删除限流配置
     */
    @Transactional
    public void deleteConfig(Long id, String operator) {
        String requestIp = getClientIp();

        IpRateLimitConfig config = configMapper.selectById(id);
        if (config == null) {
            throw new RuntimeException("配置不存在: id=" + id);
        }

        configMapper.deleteById(id);

        logger.info("🗑️ {} 删除限流配置: ID={}, IP={}", operator, id, config.getIp());

        // 记录操作日志
        OpsAuditLog log = new OpsAuditLog();
        log.setOperator(operator);
        log.setOperationType("RATE_LIMIT_DELETE");
        log.setOperationName("删除限流配置");
        log.setTarget(config.getIp());
        log.setResult(1);
        log.setResultMsg(String.format("已删除IP: %s 的限流配置 (限流: %d次/分钟)",
                config.getIp(), config.getRateLimit()));
        log.setRequestIp(requestIp);
        auditLogMapper.insert(log);
    }

    /**
     * 启用/禁用限流配置
     */
    @Transactional
    public void toggleConfig(Long id, Integer enabled, String operator) {
        String requestIp = getClientIp();

        IpRateLimitConfig config = configMapper.selectById(id);
        if (config == null) {
            throw new RuntimeException("配置不存在: id=" + id);
        }

        configMapper.toggleEnabled(id, enabled, operator);

        String status = enabled == 1 ? "启用" : "禁用";
        logger.info("{} {} 限流配置: ID={}, IP={}", operator, status, id, config.getIp());

        // 记录操作日志
        OpsAuditLog log = new OpsAuditLog();
        log.setOperator(operator);
        log.setOperationType("RATE_LIMIT_TOGGLE");
        log.setOperationName(status + "限流配置");
        log.setTarget(config.getIp());
        log.setResult(1);
        log.setResultMsg(String.format("已%s IP: %s 的限流配置", status, config.getIp()));
        log.setRequestIp(requestIp);
        auditLogMapper.insert(log);
    }

    /**
     * 手动解封IP（只记录日志，实际解封由 SecurityMonitorInterceptor 执行）
     */
    @Transactional
    public void unblockIp(String ip, String operator) {
        String requestIp = getClientIp();

        logger.info("🔓 {} 手动解封IP: {}", operator, ip);

        // 记录操作日志
        OpsAuditLog log = new OpsAuditLog();
        log.setOperator(operator);
        log.setOperationType("RATE_LIMIT_UNBLOCK");
        log.setOperationName("手动解封IP");
        log.setTarget(ip);
        log.setResult(1);
        log.setResultMsg("IP: " + ip + " 已手动解封");
        log.setRequestIp(requestIp);
        auditLogMapper.insert(log);
    }

    /**
     * 获取客户端真实IP
     */
    private String getClientIp() {
        try {
            String ip = request.getHeader("X-Forwarded-For");
            if (ip == null || ip.isEmpty() || "unknown".equalsIgnoreCase(ip)) {
                ip = request.getHeader("X-Real-IP");
            }
            if (ip == null || ip.isEmpty() || "unknown".equalsIgnoreCase(ip)) {
                ip = request.getRemoteAddr();
            }
            if (ip != null && ip.contains(",")) {
                ip = ip.split(",")[0].trim();
            }
            return ip != null ? ip : "unknown";
        } catch (Exception e) {
            return "unknown";
        }
    }
}