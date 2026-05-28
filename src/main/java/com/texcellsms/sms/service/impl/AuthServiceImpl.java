package com.texcellsms.sms.service.impl;

import CacheDB.NCacheDBLink;
import com.texcellsms.sms.controller.AuthController;
import com.texcellsms.sms.mapper.webportal.LoginAuditLogMapper;
import com.texcellsms.sms.model.entity.LoginAuditLog;
import com.texcellsms.sms.model.vo.CacheDBLonginResult;
import com.texcellsms.sms.model.vo.LoginUser;
import com.texcellsms.sms.service.AuthService;
import com.texcellsms.sms.service.TempTokenUtil;
import com.texcellsms.sms.service.TokenService;
import com.texcellsms.sms.util.AjaxResult;
import com.texcellsms.sms.util.EmailUtil;
import com.texcellsms.sms.util.IpLocationUtil;
import com.texcellsms.sms.util.SMSResponseMessage;
import com.warrenstrange.googleauth.GoogleAuthenticator;
import com.warrenstrange.googleauth.GoogleAuthenticatorKey;
import com.warrenstrange.googleauth.GoogleAuthenticatorQRGenerator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpSession;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static com.texcellsms.sms.util.SMSResponseMessage.*;

@Service
public class AuthServiceImpl implements AuthService {

    private static final Logger logger = LoggerFactory.getLogger(AuthServiceImpl.class);

    // 注入日志Mapper
    @Autowired(required = false)
    private LoginAuditLogMapper loginAuditLogMapper;

    @Resource
    private EmailUtil EmailUtil;

    // ====================== 原 login 方法（只加日志）======================
    @Override
    public AjaxResult login(AuthController.LoginRequest loginRequest, String IP) {
        long start = System.currentTimeMillis();
        TokenService tokenService = new TokenService();
        LoginUser loginUser = new LoginUser();
        loginUser.setUsername(loginRequest.getUsername());
        loginUser.setPassword(loginRequest.getPassword());
        loginUser.setIP(IP);
        CacheDBLonginResult cacheDBLonginResult = tokenService.getToken(loginUser);

        if (cacheDBLonginResult == null) {
            logger.error("[LOGIN_FAIL] USER={} | IP={} | CODE=NULL", loginRequest.getUsername(), IP);
            return AjaxResult.error();
        }

        if (!"0".equals(cacheDBLonginResult.getResult())) {
            String code = cacheDBLonginResult.getResult();
            String msg = getMsg(code);
            logger.error("[LOGIN_FAIL] USER={} | IP={} | CODE={}", loginRequest.getUsername(), IP, code);
            return new AjaxResult(Integer.parseInt(code), msg);
        }

        String whiteIP = cacheDBLonginResult.getWhitelistIP();
        boolean ipAllowed = checkIp(whiteIP, IP);
        if (!ipAllowed) {
            logger.error("[LOGIN_FAIL] USER={} | IP={} | IP_NOT_ALLOWED", loginRequest.getUsername(), IP);
            return new AjaxResult(480, "IP not in whitelist");
        }

        String safeToken = UUID.randomUUID().toString().replace("-", "");
        loginUser.setToken(safeToken);
        loginUser.setCreateTime(System.currentTimeMillis());
        tokenService.createToken(loginUser);
        loginUser.setFaKey(cacheDBLonginResult.getFAKey());
        loginUser.setEmail(cacheDBLonginResult.getEmail());
        loginUser.setEnable(cacheDBLonginResult.getIsEnable());

        logger.info("[LOGIN_SUCCESS] USER={} | IP={}", loginRequest.getUsername(), IP);
        return AjaxResult.success(loginUser);
    }

    // ====================== login2FA 全量审计日志 ======================
    @Override
    public AjaxResult login2FA(HttpSession session, AuthController.LoginRequest loginRequest, String IP, HttpServletRequest request) {
        long start = System.currentTimeMillis();
        LoginAuditLog log = buildLog(loginRequest, IP, request);
        TokenService tokenService = new TokenService();
        LoginUser loginUser = new LoginUser();
        loginUser.setUsername(loginRequest.getUsername());
        loginUser.setPassword(loginRequest.getPassword());
        loginUser.setIP(IP);
        CacheDBLonginResult cacheDBLonginResult = tokenService.getToken(loginUser);

        fillCacheDb(log, cacheDBLonginResult);

        if (cacheDBLonginResult == null) {
            saveFail(log, start, "NULL", "Auth failed");
            return AjaxResult.error();
        }
        if (!"0".equals(cacheDBLonginResult.getResult())) {
            String c = cacheDBLonginResult.getResult();
            saveFail(log, start, c, getMsg(c));
            return new AjaxResult(Integer.parseInt(c), getMsg(c));
        }

        String whiteIP = cacheDBLonginResult.getWhitelistIP();
        boolean ipAllowed = checkIp(whiteIP, IP);
        if (!ipAllowed) {
            saveFail(log, start, "480", "IP not allowed");
            return new AjaxResult(480, "IP not in whitelist");
        }

        // Google 2FA
        if ("0".equals(cacheDBLonginResult.getIsEnable())) {
            log.setLoginResult(0);
            log.setResultCode("201");
            log.setResultMessage("Waiting Google 2FA");
            log.setTwoFactorType("GOOGLE");
            log.setTwoFactorTarget(cacheDBLonginResult.getFAKey());
            log.setAuthType(1);
            log.setTwoFaResult(0);
            log.setLoginScene("2FA_REQUIRED");
            log.setLoginDuration((int) (System.currentTimeMillis() - start));
            saveAsync(log);
            return Google2FA(loginRequest, request.getRemoteHost(), cacheDBLonginResult.getFAKey(), cacheDBLonginResult);
        }

        // Email 2FA
        if ("2".equals(cacheDBLonginResult.getIsEnable())) {
            log.setLoginResult(0);
            log.setResultCode("202");
            log.setResultMessage("Waiting Email 2FA");
            log.setTwoFactorType("EMAIL");
            log.setTwoFactorTarget(cacheDBLonginResult.getEmail());
            log.setAuthType(2);
            log.setTwoFaResult(0);
            log.setLoginScene("2FA_REQUIRED");
            log.setLoginDuration((int) (System.currentTimeMillis() - start));
            saveAsync(log);
            return Email2FA(session, loginRequest, cacheDBLonginResult.getEmail(), cacheDBLonginResult);
        }

        // 登录成功
        String token = UUID.randomUUID().toString().replace("-", "");
        loginUser.setToken(token);
        loginUser.setCreateTime(System.currentTimeMillis());
        tokenService.createToken(loginUser);
        loginUser.setFaKey(cacheDBLonginResult.getFAKey());
        loginUser.setEmail(cacheDBLonginResult.getEmail());
        loginUser.setEnable(cacheDBLonginResult.getIsEnable());

        log.setLoginResult(1);
        log.setResultCode("0");
        log.setResultMessage("Success");
        log.setLoginScene("LOGIN_SUCCESS");
        log.setAuthType(0);
        log.setTwoFaResult(1);
        log.setLoginDuration((int) (System.currentTimeMillis() - start));
        saveAsync(log);

        logger.info("[LOGIN_SUCCESS] USER={}", loginRequest.getUsername());
        return AjaxResult.success(loginUser);
    }

    // ====================== 谷歌验证（日志更新） ======================
    @Override
    public AjaxResult AVerify(String Token, String code, String IP) {
        if (!TempTokenUtil.verifyTokenTimestamp(Token))
            return AjaxResult.error(AUTHORIZATIONTIMEOUT.getCode(), AUTHORIZATIONTIMEOUT.getMessage());

        String name = TempTokenUtil.getUsernameFromToken(Token);
        String pwd = TempTokenUtil.getPasswordFromToken(Token);
        AuthController.LoginRequest req = new AuthController.LoginRequest();
        req.setUsername(name);
        req.setPassword(pwd);

        AjaxResult res = login(req, IP);
        LoginUser user = res.getData();

        AjaxResult vr = GoogleAuthVerification(user, code);

        // 枚举：0=成功
        if (vr.getCode() == SMSResponseMessage.SUCCESS.getCode()) {
            update2fa(name, IP, 1, "2FA_SUCCESS");
        } else {
            update2fa(name, IP, 2, "2FA_FAILED");
        }

        return vr;
    }

    // ====================== 邮箱验证（日志更新） ======================
    @Override
    public AjaxResult AVerifyEmail(HttpSession session, String Token, String code, String IP) {
        String sc = session.getAttribute("emailCode:" + Token) + "";
        Long t = (Long) session.getAttribute("emailCodeTime:" + Token);

        if (t == null || System.currentTimeMillis() - t > 300000) {
            session.invalidate();
            return AjaxResult.error(CODEEXPIRED.getCode(), CODEEXPIRED.getMessage());
        }

        if (!sc.equals(code)) {
            update2fa(TempTokenUtil.getUsernameFromToken(Token), IP, 2, "2FA_FAILED");
            return AjaxResult.error(TWOSTEPVERIFICATIONFAILED.getCode(), TWOSTEPVERIFICATIONFAILED.getMessage());
        }

        session.invalidate();
        AuthController.LoginRequest req = new AuthController.LoginRequest();
        req.setUsername(TempTokenUtil.getUsernameFromToken(Token));
        req.setPassword(TempTokenUtil.getPasswordFromToken(Token));

        AjaxResult r = login(req, IP);

        // 枚举判断
        if (r.getCode() == SMSResponseMessage.SUCCESS.getCode()) {
            update2fa(req.getUsername(), IP, 1, "2FA_SUCCESS");
        }

        return r;
    }

    // ====================== 日志工具 ======================
    private LoginAuditLog buildLog(AuthController.LoginRequest r, String ip, HttpServletRequest rq) {
        LoginAuditLog log = new LoginAuditLog();
        log.setLogUuid(UUID.randomUUID().toString());
        log.setUsername(r.getUsername());
        log.setRealIp(ip);
        log.setLoginTime(LocalDateTime.now());
        try {
            IpLocationUtil.IpLocationInfo info = IpLocationUtil.getLocation(ip);
            log.setIpCountry(info.getCountry());
            log.setIpRegion(info.getRegion());
            log.setIpCity(info.getCity());
            log.setIpIsp(info.getIsp());
            log.setIpLocation(info.getLocation());
        } catch (Exception ignored) {}
        log.setSourceDomain(cut(rq.getHeader("host"), 255));
        log.setRequestMethod(rq.getMethod());
        log.setRequestUri(cut(rq.getRequestURI(), 500));
        log.setUserAgent(cut(rq.getHeader("User-Agent"), 500));
        log.setReferer(cut(rq.getHeader("referer"), 500));
        log.setCfConnectingIp(cut(rq.getHeader("CF-Connecting-IP"), 45));
        log.setXRealIp(cut(rq.getHeader("X-Real-IP"), 45));
        log.setXForwardedFor(cut(rq.getHeader("X-Forwarded-For"), 255));
        log.setRemoteAddr(rq.getRemoteAddr());
        log.setCreateTime(LocalDateTime.now());
        log.setUpdateTime(LocalDateTime.now());
        return log;
    }

    private void fillCacheDb(LoginAuditLog log, CacheDBLonginResult r) {
        if (log == null || r == null) return;
        log.setCacheDbRawResult(r.getResult());
        log.setCacheDbWhitelistIp(r.getWhitelistIP());
        log.setCacheDbFaKey(r.getFAKey());
        log.setCacheDbIsEnable("1".equals(r.getIsEnable()) ? 1 : 0);
    }

    private void saveFail(LoginAuditLog log, long s, String c, String m) {
        log.setLoginResult(0);
        log.setResultCode(c);
        log.setResultMessage(m);
        log.setLoginScene("DEFAULT");
        log.setAuthType(0);
        log.setTwoFaResult(0);
        log.setLoginDuration((int) (System.currentTimeMillis() - s));
        saveAsync(log);
    }

    private void saveAsync(LoginAuditLog log) {
        if (loginAuditLogMapper == null) return;
        CompletableFuture.runAsync(() -> {
            try { loginAuditLogMapper.insert(log); } catch (Exception e) { logger.error("save log err", e); }
        });
    }

    private void update2fa(String username, String ip, int res, String scene) {
        if (loginAuditLogMapper == null) return;
        CompletableFuture.runAsync(() -> {
            loginAuditLogMapper.update2FaResult(username, ip, res, scene);
        });
    }

    private String cut(String s, int l) {
        return s == null ? null : s.length() <= l ? s : s.substring(0, l);
    }

    private boolean checkIp(String w, String ip) {
        if (w == null || w.trim().isEmpty() || "%".equals(w.trim())) return true;
        for (String s : w.split("[;,\\s]+")) if (s.trim().equals(ip)) return true;
        return false;
    }

    private String getMsg(String c) {
        switch (c) {
            case "402": return ACCOUNTERROR.getMessage();
            case "403": return AUTHENTICATIONERROR.getMessage();
            case "405": return PARAMETERERROR.getMessage();
            case "480": return IPLOCKEDERROR.getMessage();
            case "502": return CACHEDBERROR.getMessage();
            case "603": return ACCOUNTLOCKEDERROR.getMessage();
            default: return "Login failed";
        }
    }

    // ====================== 原有代码不动 ======================
    public AjaxResult Google2FA(AuthController.LoginRequest loginRequest, String host, String okey, CacheDBLonginResult result) {
        NCacheDBLink nCacheDBLink = null;
        String secretKey = null;
        String qrCodeData = null;
        int count = -1;
        if (okey == null || okey.equals("")) {
            try {
                GoogleAuthenticator gAuth = new GoogleAuthenticator();
                GoogleAuthenticatorKey key = gAuth.createCredentials();
                secretKey = key.getKey();
                nCacheDBLink = NCacheDBLink.getNCacheDBLink();
                count = nCacheDBLink.InitEnv();
                nCacheDBLink.ConnectToCacheDB();
                nCacheDBLink.prc_Set2FAKeyForCompanyId(secretKey, loginRequest.getUsername());
                qrCodeData = GoogleAuthenticatorQRGenerator.getOtpAuthURL(loginRequest.getUsername(), host, key);
            } catch (Exception e) {
                e.printStackTrace();
                return AjaxResult.error("Failed to Google2FA");
            } finally {
                if (nCacheDBLink != null || count > 0) {
                    try { nCacheDBLink.Disconnect(); } catch (Exception ignored) {}
                    try { nCacheDBLink.Destroy(); } catch (Exception ignored) {}
                }
            }
        }
        LoginUser loginUser = new LoginUser();
        loginUser.setSManualKey(secretKey);
        loginUser.setSQRCode(qrCodeData);
        loginUser.setToken(TempTokenUtil.generateToken(loginRequest.getUsername(), loginRequest.getPassword()));
        loginUser.setFaKey(result.getFAKey());
        loginUser.setEmail(result.getEmail());
        loginUser.setEnable(result.getIsEnable());
        return AjaxResult.success(LOGIN2FA.getCode(), LOGIN2FA.getMessage(), loginUser);
    }

    public AjaxResult Email2FA(HttpSession session, AuthController.LoginRequest loginRequest, String email, CacheDBLonginResult result) {
        String sessionEmail = (String) session.getAttribute(email);
        if (sessionEmail != null) {
            return AjaxResult.error(FREQUENTEMAILSENDING.getCode(), FREQUENTEMAILSENDING.getMessage());
        }
        if (email == null || email.isEmpty()) {
            return AjaxResult.error(EMAILDOESNOTEXIST.getCode(), EMAILDOESNOTEXIST.getMessage());
        }
        String code = EmailUtil.generateCode();
        boolean success = EmailUtil.sendEmailCode(email, code);
        if (success) {
            LoginUser loginUser = new LoginUser();
            loginUser.setToken(TempTokenUtil.generateToken(loginRequest.getUsername(), loginRequest.getPassword()));
            loginUser.setEmail(email);
            loginUser.setFaKey(result.getFAKey());
            loginUser.setEnable(result.getIsEnable());
            session.setAttribute("emailCode:" + loginUser.getToken(), code);
            session.setAttribute("emailCodeTime:" + loginUser.getToken(), System.currentTimeMillis());
            session.setAttribute("email:" + loginUser.getToken(), email);
            session.setAttribute(email, email);
            session.setMaxInactiveInterval(5 * 60);
            return AjaxResult.success(EMAILSUCCESSFULLY.getCode(), EMAILSUCCESSFULLY.getMessage(), loginUser);
        }
        return AjaxResult.error(EMAILFAILED.getCode(), EMAILFAILED.getMessage());
    }

    public AjaxResult GoogleAuthVerification(LoginUser loginUser, String userProvidedCode) {
        GoogleAuthenticator gAuth = new GoogleAuthenticator();
        if (loginUser.getFaKey() == null) {
            return AjaxResult.error(BIND2AT.getCode(), BIND2AT.getMessage());
        }
        boolean isValid = gAuth.authorize(loginUser.getFaKey(), Integer.parseInt(userProvidedCode));
        if (!isValid) {
            return AjaxResult.error(TWOSTEPVERIFICATIONFAILED.getCode(), TWOSTEPVERIFICATIONFAILED.getMessage());
        }
        return AjaxResult.success(loginUser);
    }

    @Override
    public AjaxResult getBalance(String Username) {
        NCacheDBLink nCacheDBLink = null;
        int count = -1;
        try {
            nCacheDBLink = NCacheDBLink.getNCacheDBLink();
            count = nCacheDBLink.InitEnv();
            nCacheDBLink.ConnectToCacheDB();
            Map<String, Object> map = nCacheDBLink.prc_MMSIFQueryBalance(Username);
            BigDecimal b1 = new BigDecimal((String) map.get("Balance"));
            map.put("Balance", b1.divide(new BigDecimal("10000")));
            return AjaxResult.success(map);
        } catch (Exception e) {
            e.printStackTrace();
            return AjaxResult.error("Failed to query balance");
        } finally {
            if (nCacheDBLink != null || count > 0) {
                try { nCacheDBLink.Disconnect(); } catch (Exception ignored) {}
                try { nCacheDBLink.Destroy(); } catch (Exception ignored) {}
            }
        }
    }

    @Override
    public AjaxResult getRate(String Username, String MCC, String MNC) {
        NCacheDBLink nCacheDBLink = null;
        int count = -1;
        try {
            nCacheDBLink = NCacheDBLink.getNCacheDBLink();
            count = nCacheDBLink.InitEnv("192.168.120.250", 18801, "auth", "auth", "auth", "utf-8", "utf-8");
            nCacheDBLink.ConnectToCacheDB();
            List<Map<String, String>> list = nCacheDBLink.web_QueryUserRate(Username, MCC, MNC);
            return AjaxResult.success(list);
        } catch (Exception e) {
            e.printStackTrace();
            return AjaxResult.error("Failed to query rate");
        } finally {
            if (nCacheDBLink != null || count > 0) {
                try { nCacheDBLink.Disconnect(); } catch (Exception ignored) {}
                try { nCacheDBLink.Destroy(); } catch (Exception ignored) {}
            }
        }
    }

    @Override
    public AjaxResult ChangeSwitchAccountPWD(String Username, String OldPass, String NewPass) {
        NCacheDBLink nCacheDBLink = null;
        int count = -1;
        try {
            nCacheDBLink = NCacheDBLink.getNCacheDBLink();
            count = nCacheDBLink.InitEnv();
            nCacheDBLink.ConnectToCacheDB();
            return AjaxResult.success(nCacheDBLink.prc_ChangeSwitchAccountPWD(Username, OldPass, NewPass));
        } catch (Exception e) {
            e.printStackTrace();
            return AjaxResult.error("Failed to change password");
        } finally {
            if (nCacheDBLink != null || count > 0) {
                try { nCacheDBLink.Disconnect(); } catch (Exception ignored) {}
                try { nCacheDBLink.Destroy(); } catch (Exception ignored) {}
            }
        }
    }

    @Override
    public AjaxResult Update2FAVerify(String name, String key, String Enable, String Email, String OpType) {
        NCacheDBLink nCacheDBLink = null;
        int count = -1;
        try {
            nCacheDBLink = NCacheDBLink.getNCacheDBLink();
            count = nCacheDBLink.InitEnv();
            nCacheDBLink.ConnectToCacheDB();
            return AjaxResult.success("4".equals(OpType)
                    ? nCacheDBLink.prc_Set2FAKeyForCompanyId("", name)
                    : nCacheDBLink.prc_SetCompany2FA_Condition(name, Enable, Email, OpType));
        } catch (Exception e) {
            e.printStackTrace();
            return AjaxResult.error("Failed to UpdateAVerify");
        } finally {
            if (nCacheDBLink != null || count > 0) {
                try { nCacheDBLink.Disconnect(); } catch (Exception ignored) {}
                try { nCacheDBLink.Destroy(); } catch (Exception ignored) {}
            }
        }
    }
}