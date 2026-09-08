package com.hmdp.service.impl;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.CacheClient;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.RedisData;
import jakarta.annotation.Resource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.CACHE_SHOP_KEY;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {
    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private CacheClient cacheClient;
    private static final ExecutorService executorService = Executors.newFixedThreadPool(10);//获取线程池
    @Override
    public Object queryById(Long id) {
        //return queryByIdWithMutex(id);
        return cacheClient.queryByIdWithPassThrough(id, Shop.class, CACHE_SHOP_KEY, this::getById, RedisConstants.CACHE_SHOP_TTL, TimeUnit.MINUTES);
    }



    private Shop queryByIdWithLogicalExpire(Long id) {
        //查redis
        String shopJson = stringRedisTemplate.opsForValue().get(CACHE_SHOP_KEY + id);
        //存在返回
        if(StrUtil.isBlank(shopJson)){
            return null;
        }
        RedisData redisData = JSONUtil.toBean(shopJson, RedisData.class);
        Shop shop = JSONUtil.toBean(JSONUtil.toJsonStr(redisData.getData()), Shop.class);
        LocalDateTime expireTime = redisData.getExpireTime();

        if(expireTime.isAfter(LocalDateTime.now())){
            return shop;
        }
        else {
            boolean getlock = getlock(RedisConstants.LOCK_SHOP_KEY + id);

            if(getlock) {
                //二次确认
                String newshopJson = stringRedisTemplate.opsForValue().get(CACHE_SHOP_KEY + id);
                // 2. 转换为 RedisData 对象
                RedisData newRedisData = JSONUtil.toBean(newshopJson, RedisData.class);
                // 3. 获取最新的过期时间
                LocalDateTime newExpireTime = newRedisData.getExpireTime();

                // 4. 【核心】再次判断是否过期
                if (newExpireTime.isAfter(LocalDateTime.now())) {
                    // 发现时间已经变成“未过期”了，说明别人趁我排队时已经更新好缓存了
                    unlock(RedisConstants.LOCK_SHOP_KEY + id); // 释放锁，直接退出，不需要查库了
                    return JSONUtil.toBean(JSONUtil.toJsonStr(newRedisData), Shop.class);
                }

                executorService.submit(() -> {
                    try {
                        Shop newshop = getById(id);
                        RedisData newredisData = new RedisData();
                        newredisData.setData(newshop);
                        newredisData.setExpireTime(LocalDateTime.now().plusMinutes(RedisConstants.CACHE_SHOP_TTL));
                        stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY + id, JSONUtil.toJsonStr(newredisData));
                        return newshop;
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    } finally {
                        unlock(RedisConstants.LOCK_SHOP_KEY + id);
                    }
                });

            }
        }
        //存在写入redis返回
        return shop;
    }



    private Shop queryByIdWithMutex(Long id) {
        //查redis
        String shopJson = stringRedisTemplate.opsForValue().get(CACHE_SHOP_KEY + id);
        //存在返回
        if(StrUtil.isNotBlank(shopJson)){
            return JSONUtil.toBean(shopJson, Shop.class);
        }
        if(shopJson!=null){
            return null;
        }
        Shop shop =new Shop();
        try {
            boolean isnotLocked = getlock(RedisConstants.LOCK_SHOP_KEY + id);
            if(!isnotLocked){
                Thread.sleep(50);

                queryByIdWithMutex(id);
            }
            shop = getById(id);
            if(shop == null) {
                stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY + id, "", (RedisConstants.CACHE_NULL_TTL), TimeUnit.MINUTES);
                return null;
            }
            stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY + id, JSONUtil.toJsonStr(shop),(RedisConstants.CACHE_SHOP_TTL),TimeUnit.MINUTES);

        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        } finally {unlock(RedisConstants.LOCK_SHOP_KEY + id);
        }


        //存在写入redis返回
        return shop;
    }
    //缓存穿透解决方案
    private Shop queryByIdWithPassThrough(Long id) {
        //查redis
        String shopJson = stringRedisTemplate.opsForValue().get(CACHE_SHOP_KEY + id);
        //存在返回
        if(StrUtil.isNotBlank(shopJson)){
           return JSONUtil.toBean(shopJson, Shop.class);
        }
        if(shopJson!=null){

                return null;
        }

        //不存在查数据库
        Shop shop = getById(id);
        //不存在false
        if(shop == null){

            stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY + id, "",(RedisConstants.CACHE_NULL_TTL),TimeUnit.MINUTES);

            return null;
        }
        stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY + id, JSONUtil.toJsonStr(shop),(RedisConstants.CACHE_SHOP_TTL),TimeUnit.MINUTES);


        //存在写入redis返回
        return shop;
    }

    @Override
    @Transactional
    public Object update(Shop shop) {
        Long id = shop.getId();
        if(id == null){
            return Result.fail("店铺id不能为空");
        }
        updateById(shop);
        stringRedisTemplate.delete(CACHE_SHOP_KEY + id);
        return Result.ok();


    }

    public boolean getlock(String key){
        return stringRedisTemplate.opsForValue().setIfAbsent(key,"1",RedisConstants.LOCK_SHOP_TTL, TimeUnit.MINUTES);
    }

    public boolean unlock(String key){
        return stringRedisTemplate.delete(key);
    }
}
