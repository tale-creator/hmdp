package com.hmdp.service.impl;

import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.ShopType;
import com.hmdp.mapper.ShopTypeMapper;
import com.hmdp.service.IShopTypeService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisConstants;
import jakarta.annotation.Resource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopTypeServiceImpl extends ServiceImpl<ShopTypeMapper, ShopType> implements IShopTypeService {
    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Override
    public List<ShopType> queryShopList() {
        List<String> shoptype = stringRedisTemplate.opsForList().range("Cache:ShopType:", 0, -1);
        if(!shoptype.isEmpty()){
            return shoptype.stream().map(s-> JSONUtil.toBean(s, ShopType.class)).collect(Collectors.toList());
        }
        List<ShopType> shopTypes = query().orderByAsc("sort").list();
        if(shopTypes.size()==0){
            return null;
        }
        stringRedisTemplate.opsForList().rightPushAll("Cache:ShopType:", shopTypes.stream().map(s-> JSONUtil.toJsonStr(s)).collect(Collectors.toList()));
        return shopTypes;


    }
}
