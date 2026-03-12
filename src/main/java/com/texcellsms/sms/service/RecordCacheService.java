package com.texcellsms.sms.service;




import com.alibaba.fastjson2.JSON;
import com.texcellsms.sms.model.DTO.SubmitRecordDTO;


import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;


import javax.annotation.Resource;
import java.util.List;
import java.util.concurrent.TimeUnit;

@Component
public class RecordCacheService {

    @Resource
    private RedisTemplate<String, String> redisTemplate;

    // 缓存前缀
    private static final String CACHE_PREFIX = "submit_record:";
    // 缓存过期时间（30分钟，适配用户几分钟查询一次的场景）
    private static final long CACHE_EXPIRE_MINUTES = 30;

    /**
     * 缓存查询结果
     */
    public void cacheRecords(String key, List<SubmitRecordDTO> records) {
        String cacheKey = CACHE_PREFIX + key;
        String json = JSON.toJSONString(records);
        redisTemplate.opsForValue().set(cacheKey, json, CACHE_EXPIRE_MINUTES, TimeUnit.MINUTES);
    }

    /**
     * 获取缓存结果
     */
    public List<SubmitRecordDTO> getCachedRecords(String key) {
        String cacheKey = CACHE_PREFIX + key;
        String json = redisTemplate.opsForValue().get(cacheKey);
        if (json == null || json.isEmpty()) {
            return null;
        }
        return JSON.parseArray(json, SubmitRecordDTO.class);
    }

    /**
     * 清除指定缓存
     */
    public void clearCache(String key) {
        String cacheKey = CACHE_PREFIX + key;
        redisTemplate.delete(cacheKey);
    }

    /**
     * 生成缓存Key（用户账号+时间范围）
     */
    public String generateCacheKey(String sourAccount, String startTime, String endTime) {
        return sourAccount + "_" + startTime.replaceAll("/|\\s|:", "") + "_" + endTime.replaceAll("/|\\s|:", "");
    }
}