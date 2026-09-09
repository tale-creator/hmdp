package com.hmdp.utils;

import jakarta.annotation.Resource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
@Component
public class RedisWorker {
    @Resource
    private StringRedisTemplate stringRedisTemplate;
    public RedisWorker(StringRedisTemplate stringRedisTemplate) {
    }

    public static final long begintime=1788825600L;

    public long nextId(String keyPre){
        LocalDateTime now = LocalDateTime.now();
        long nowSecond = now.toEpochSecond(ZoneOffset.UTC);
        long timestamp=nowSecond-begintime;
        String date =now.format(DateTimeFormatter.ofPattern("yyyy:MM:dd"));
        long count = stringRedisTemplate.opsForValue().increment("icr:" + keyPre + ":" + date);

        return timestamp<<32 | count;
    }




}
