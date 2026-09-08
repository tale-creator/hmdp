package com.hmdp.utils;


import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.hmdp.entity.Shop;
import jakarta.annotation.Resource;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import javax.print.DocFlavor;
import java.time.LocalDateTime;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static com.hmdp.utils.RedisConstants.CACHE_SHOP_KEY;

@Component
public class CacheClient {
    @Resource
    private StringRedisTemplate stringRedisTemplate;





    public boolean getlock(String key, Long timeout, TimeUnit unit){
        return stringRedisTemplate.opsForValue().setIfAbsent(key,"1",timeout, unit);
    }

    public boolean unlock(String key){
        return stringRedisTemplate.delete(key);
    }



    //缓存穿透
    public <T,ID> T queryByIdWithPassThrough
            (ID id, Class<T> type, String keyprefix, Function<ID, T> dbFallback, Long time, TimeUnit unit) {
        //查redis
        String key =keyprefix+id;
        String Json = stringRedisTemplate.opsForValue().get(key);
        //存在返回
        if(StrUtil.isNotBlank(Json)){
            return JSONUtil.toBean(Json, type);
        }
        if(Json!=null){

            return null;
        }

        //不存在查数据库
        T t = dbFallback.apply(id);
        //不存在false
        if(t == null){

            stringRedisTemplate.opsForValue().set(key, "",(RedisConstants.CACHE_NULL_TTL),TimeUnit.MINUTES);

            return null;
        }
        this.setCache(key, t, time, unit, type);


        //存在写入redis返回
        return t;
    }


    //设置缓存
    public <T> void setCache(String key, Object value, Long time, TimeUnit unit, Class<T> type) {
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(value), time, unit);
    }


    //设置逻辑过期缓存
    public <T> void setLogicalExireCache(String key, T value, Long time, TimeUnit unit, Class<T> type) {
        RedisData redisData = new RedisData();
        redisData.setData(value);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(unit.toSeconds(time)));
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(redisData));
    }
}
