package com.hmdp.utils;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

// 缓存工具类
@Slf4j
@Component
public class CacheClient {
    private StringRedisTemplate stringRedisTemplate;

    public CacheClient(StringRedisTemplate stringRedisTemplate){
        this.stringRedisTemplate = stringRedisTemplate;
    }

    public void set(String key, Object value, Long time, TimeUnit unit){
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(value), time, unit);
    }


    // redis设置逻辑过期
    public void setWithLogicalExpire(String key, Object value, Long time, TimeUnit unit){
        // 设置逻辑过期
        RedisData redisData = new RedisData();
        redisData.setData(value);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(unit.toSeconds(time)));
        // 写入redis
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(redisData));
    }

    // 缓存穿透 -> 缓存空对象
    public <R, ID> R queryWithPassThrough(String keyPrefix, ID id, Class<R> type, Function<ID, R> dbFallback, Long time, TimeUnit unit){
        String key = keyPrefix + id;
        String json = stringRedisTemplate.opsForValue().get(key);
        // redis缓存命中
        if (StrUtil.isNotBlank(json)){
            return JSONUtil.toBean(json, type);
        }
        // 缓存的是空值
        if (json != null)
            return null;
        // 去数据库中进行查询
        R r = dbFallback.apply(id);
        if (r == null){
            // 缓存空对象
            stringRedisTemplate.opsForValue().set(key, "", RedisConstants.CACHE_NULL_TTL, TimeUnit.MINUTES);
            return null;
        }
        this.set(key, r, time, unit);
        return r;
    }

    // 线程池 -> for logicalExpire
    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);

    /**
     *
     * @param keyPrefix key前缀
     * @param lockPrefix 互斥锁key前缀
     * @param id 查询ID
     * @param type 查询结果类型
     * @param dbFallback 操作数据库函数
     * @param lock_ttl 互斥锁过期时间
     * @param time 缓存重建时设置过期时间
     * @param unit 缓存重建时设置过期时间单位
     * @return 查询结果
     * @param <R> 查询结果类型
     * @param <ID> 查询ID
     */
    public <R, ID> R queryWithLogicalExpire(String keyPrefix, String lockPrefix, ID id, Class<R> type, Function<ID, R> dbFallback, Long lock_ttl, Long time, TimeUnit unit) {
        String key = keyPrefix + id;
        String locKey = lockPrefix + id;
        // 1. redis获取数据
        String json = stringRedisTemplate.opsForValue().get(key);
        // 2. 判断是否存在，不存在直接返回空
        if (StrUtil.isBlank(json)) {
            // 2.1 此时不存在，直接返回空值
            return null;
        }

        // 3. 此时redis中命中缓存，先解析对象，再读取逻辑过期时间
        RedisData redisData = JSONUtil.toBean(json, RedisData.class);
        // 这里的data对象其实底层是JsonObject, 因为我们本来就是object类型，反序列化器不知道反序列化为啥
        // 于是类默认序列化为了JsonObject
        JSONObject data = (JSONObject) redisData.getData();
        R r = JSONUtil.toBean(data, type);
        // 获取过期时间
        LocalDateTime expireTime = redisData.getExpireTime();
        // 4. 判断是否过期
        if (expireTime.isAfter(LocalDateTime.now())){
            // 4.1 此时代表还没过期，数据可以直接用
            return r;
        }
        // 5. 此时数据已经过期了，需要竞争互斥锁，成功的话会开启一个线程来完成查表重建缓存动作
        boolean isLock = tryLock(locKey, lock_ttl);
        if (isLock){
            // 拿到互斥锁，开启新线程来完成缓存重建动作
            CACHE_REBUILD_EXECUTOR.submit(() -> {
                try {
                    // 查询数据库
                    R r1 = dbFallback.apply(id);
                    // 写入redis
                    this.setWithLogicalExpire(key, r1, time, unit);
                    log.debug("重建缓存成功");
                } catch (Exception e){
                    e.printStackTrace(System.out);
                    throw new RuntimeException(e);
                } finally {
                    // 释放锁
                    unLock(locKey);
                }
            });
            log.debug("old object is {}", r);
        }

        // 6. 返回旧的查询对象信息
        return r;
    }

    private boolean tryLock(String key, Long lock_ttl) {
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", lock_ttl, TimeUnit.SECONDS);
        return BooleanUtil.isTrue(flag);
    }

    private void unLock(String key) {
        stringRedisTemplate.delete(key);
    }

}
