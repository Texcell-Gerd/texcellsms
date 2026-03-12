package com.texcellsms.sms.service;

import CacheDB.NCacheDBLink;

import com.texcellsms.sms.model.vo.CacheDBLonginResult;
import com.texcellsms.sms.model.vo.LoginUser;
import com.texcellsms.sms.util.Constants;
import com.texcellsms.sms.util.ServletUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;
import org.springframework.stereotype.Service;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpSession;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;


/**
 * token验证处理
 *
 * @author lucky
 */
@Component
@Service
public class TokenService
{

    private final static long EXPIRE_TIME = Constants.TOKEN_EXPIRE * 60;

    protected static final long MILLIS_SECOND = 1000;

    private static final NCacheDBLink CacheDBLink=NCacheDBLink.getNCacheDBLink();
    /**
     * 获取用户
     */
    public static String getUsername()
    {
        String username=null;
        String token = getToken(ServletUtils.getRequest());
        if (StringUtils.isNotEmpty(token))
        {
            Map<String,String> map;
            map = (Map<String, String>) ServletUtils.getSession().getServletContext().getAttribute(token);
            username = map.get("Username");
        }
        return username;
    }


    public static String getUsername(String Authorization)
    {
        String username=null;
        if (StringUtils.isNotEmpty(Authorization))
        {
            Map<String,String> map;
            map = (Map<String, String>) ServletUtils.getSession().getServletContext().getAttribute(Authorization);
            username = map.get("Username");
        }
        return username;
    }

//    /**
//     * 获取用户ID
//     */
//    public static Long getUserId()
//    {
//        String userid=null;
//        String token = getToken(ServletUtils.getRequest());
//        if (StringUtils.isNotEmpty(token))
//        {
//            String userKey = CacheConstants.LOGIN_TOKEN_KEY+token;
//            JSONObject obj = (JSONObject) JSONObject.toJSON(redisStaticService.getCacheObject(userKey));
//            userid = obj.getString("userid");
//        }
//        return Convert.toLong(userid);
//    }

    /**
     * 获取请求token
     */
    private static String getToken(HttpServletRequest request)
    {
        String token = request.getHeader(Constants.HEADER);
        if (token==null||token.equals(""))
        {
            token = token.replace(Constants.HEADER, "");
        }
        return token;
    }


    /**
     * 创建令牌
     */
    public void createToken(LoginUser loginUser)
    {
        // 生成token
//        String token = getToken(loginUser);
//        loginUser.setToken(token);
//        loginUser.setUsername(loginUser.getUsername());
//        refreshToken(loginUser);
        // 保存或更新用户token
//        Map<String, Object> map = new HashMap<String, Object>();
//        map.put(Constants.HEADER, token);
//        map.put("expires_in", EXPIRE_TIME);
//        if (token.equals("")||token==null){
//            throw new RuntimeException("Login error");
//        }
//        redisService.setCacheObject(ACCESS_TOKEN + token, loginUser, EXPIRE_TIME, TimeUnit.SECONDS);
        HttpSession session = ServletUtils.getSession();
        Map<String,String> map = new HashMap<>();
        map.put("Username",loginUser.getUsername());
        map.put("IP",loginUser.getIP());
        map.put(Constants.HEADER,loginUser.getToken());
        session.getServletContext().setAttribute(loginUser.getToken(),map);
        session.setMaxInactiveInterval(1000*60*60);
    }

    public synchronized CacheDBLonginResult getToken(LoginUser loginUser){
      CacheDBLink.InitEnv();
      CacheDBLink.ConnectToCacheDB();
      Map<String, Object> map =  CacheDBLink.ExecProc_QueryGWUserPass_With2FA(loginUser.getUsername(),loginUser.getPassword(),loginUser.getIP());
      String   Result = (String) map.get("Result");
      String   WhitelistIP = (String) map.get("WhitelistIP");
      String   FAKey = (String) map.get("FAKey");
      String   IsEnable = (String) map.get("IsEnable");
      CacheDBLonginResult cacheDBLonginResult = new CacheDBLonginResult(Result,WhitelistIP,FAKey,IsEnable);
      CacheDBLink.Disconnect();
      CacheDBLink.Destroy();
      return cacheDBLonginResult;
    };


    public static String getIpAddress(HttpServletRequest request) {
        String ip = request.getHeader("x-forwarded-for");
        if (ip == null || ip.length() == 0 || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getHeader("Proxy-Client-IP");
        }
        if (ip == null || ip.length() == 0 || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getHeader("WL-Proxy-Client-IP");
        }
        if (ip == null || ip.length() == 0 || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getHeader("HTTP_CLIENT_IP");
        }
        if (ip == null || ip.length() == 0 || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getHeader("HTTP_X_FORWARDED_FOR");
        }
        if (ip == null || ip.length() == 0 || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getRemoteAddr();
        }
// 处理多级代理的情况
        if (ip != null && ip.length() != 0) {
            String[] ips = ip.split(",");
            for (String strIp : ips) {
                if (!"unknown".equalsIgnoreCase(strIp)) {
                    ip = strIp;
                    break;
                }
            }
        }
        return ip;
    }

    /**
     * 获取用户身份信息
     *
     * @return 用户信息
     */
//    public LoginUser getLoginUser()
//    {
//        return getLoginUser(ServletUtils.getRequest());
//    }

    /**
     * 获取用户身份信息
     *
     * @return 用户信息
     */
//    public LoginUser getLoginUser(HttpServletRequest request)
//    {
//        // 获取请求携带的令牌
//        String token = SecurityUtils.getToken(request);
//        if (StringUtils.isNotEmpty(token))
//        {
//            String userKey = getTokenKey(token);
//            LoginUser user = redisService.getCacheObject(userKey);
//            return user;
//        }
//        return null;
//    }

//    public static LoginUser getStaticLoginUser()
//    {
//        HttpServletRequest  request= ServletUtils.getRequest();
//        // 获取请求携带的令牌
//        String token = SecurityUtils.getToken(request);
//        if (StringUtils.isNotEmpty(token))
//        {
//            String userKey = ACCESS_TOKEN +token;
////            LoginUser user = redisStaticService.getCacheObject(userKey);
//            LoginUser user = redisStaticService.getCacheObject(userKey);
////            LoginUser user =  JSONArray.parseObject(((JSONObject)redisStaticService.getCacheObject(userKey)).toString(),LoginUser.class);
//            return user;
//        }
//        return null;
//    }
//
//    public static SysUser getSysUser()
//    {
//        HttpServletRequest  request= ServletUtils.getRequest();
//        // 获取请求携带的令牌
//        String token = SecurityUtils.getToken(request);
//        if (StringUtils.isNotEmpty(token))
//        {
//            String userKey = ACCESS_TOKEN +token;
//            LoginUser  loginuser = redisStaticService.getCacheObject(userKey);
//            SysUser user = loginuser.getSysUser();
////            SysUser user =   ((JSONObject)redisStaticService.getCacheObject(userKey)).getJSONObject("sysUser").toJavaObject(SysUser.class);
//            return user;
//        }
//        return null;
//    }
//
//
//
//
//    /**
//     * 设置用户身份信息
//     */
//    public void setLoginUser(LoginUser loginUser)
//    {
//        if (StringUtils.isNotNull(loginUser) && StringUtils.isNotEmpty(loginUser.getToken()))
//        {
//            refreshToken(loginUser);
//        }
//    }
//
//    public void delLoginUser(String token)
//    {
//        if (StringUtils.isNotEmpty(token))
//        {
//            String userKey = getTokenKey(token);
//            redisService.deleteObject(userKey);
//        }
//    }
//
    /**
     * 刷新令牌有效期
     *
     * @param loginUser 登录信息
     */
    public static String refreshToken(String Authorization)
    {
       if (Authorization==null||Authorization.equals("")){
           return null;
       }
        Map<String,String> map;
        map = (Map<String, String>) ServletUtils.getSession().getServletContext().getAttribute(Authorization);
        String Token = UUID.randomUUID().toString().replace("-", "") +Authorization.substring(Math.min(Authorization.length(), 32));
        map.put(Constants.HEADER,Token);
        HttpSession session = ServletUtils.getSession();
        new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        Thread.sleep(30000);
                    }catch (Exception e){}
                    session.getServletContext().removeAttribute(Authorization);
                }
         }).start();
        session.getServletContext().setAttribute(Token,map);
//        session.getServletContext().removeAttribute(Authorization);
        return Token;
    }

//    private String getTokenKey(String token)
//    {
//        return ACCESS_TOKEN + token;
//    }

}
