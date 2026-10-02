package com.hmdp.config;

import com.hmdp.entity.Shop;
import com.hmdp.service.IShopService;
import com.hmdp.utils.RedisConstants;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.data.geo.Point;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 启动时把商铺坐标灌进 Redis GEO，供"按类型 + 距离排序"使用。
 * <p>
 * 黑马点评原本是在测试类里手动执行的，这里改成自动：Redis 被清空后重启一下就能自动补回来，
 * 不用记得去跑哪个测试方法。
 * <p>
 * 只有对应类型在 GEO 里为空时才写入，所以重复启动几乎没有开销。
 */
@Slf4j
@Component
public class ShopGeoInitializer implements ApplicationRunner {

    @Resource
    private IShopService shopService;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public void run(ApplicationArguments args) {
        try {
            List<Shop> shops = shopService.list();
            if (shops.isEmpty()) {
                log.warn("商铺表为空，跳过 GEO 初始化");
                return;
            }

            // 按 typeId 分组：GEO 的 key 是 shop:geo:<typeId>
            Map<Long, Map<String, Point>> grouped = new HashMap<>();
            for (Shop shop : shops) {
                if (shop.getId() == null || shop.getTypeId() == null
                        || shop.getX() == null || shop.getY() == null) {
                    continue;
                }
                grouped.computeIfAbsent(shop.getTypeId(), k -> new HashMap<>())
                        .put(shop.getId().toString(), new Point(shop.getX(), shop.getY()));
            }

            List<Long> loaded = new ArrayList<>();
            for (Map.Entry<Long, Map<String, Point>> entry : grouped.entrySet()) {
                String key = RedisConstants.SHOP_GEO_KEY + entry.getKey();
                Long existing = stringRedisTemplate.opsForZSet().zCard(key);
                if (existing != null && existing > 0) {
                    continue;
                }
                stringRedisTemplate.opsForGeo().add(key, entry.getValue());
                loaded.add(entry.getKey());
            }

            if (loaded.isEmpty()) {
                log.info("商铺坐标已存在，无需加载（共 {} 个类型）", grouped.size());
            } else {
                log.info("商铺坐标已写入 Redis GEO，本次加载类型 {} 个，共 {} 家店", loaded, shops.size());
            }
        } catch (Exception e) {
            // 加载失败不影响其他功能，只是"按距离排序"会退化成按类型分页
            log.error("加载商铺坐标失败，按距离排序将不可用", e);
        }
    }
}
