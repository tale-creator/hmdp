package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Follow;
import com.hmdp.mapper.FollowMapper;
import com.hmdp.service.IFollowService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.service.IUserService;
import com.hmdp.utils.UserHolder;
import jakarta.annotation.Resource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Set;
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
public class FollowServiceImpl extends ServiceImpl<FollowMapper, Follow> implements IFollowService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Resource
    private IUserService userService;
    @Override
    public Result follow(Long followUserId, Boolean isFollow) {
        Long userid = UserHolder.getUser().getId();
        if(isFollow){
            boolean save = save(new Follow().setUserId(userid).setFollowUserId(followUserId));
            if(save){
                stringRedisTemplate.opsForSet().add("follows:" + userid, followUserId.toString());
            }
        }else{
            boolean remove = remove(new QueryWrapper<Follow>().eq("user_id", userid).eq("follow_user_id", followUserId));
            if(remove){
                stringRedisTemplate.opsForSet().remove("follows:" + userid, followUserId.toString());
            }
        }
        return Result.ok();
        }




    @Override
    public Result isFollow(Long followUserId) {
        Long userid = UserHolder.getUser().getId();
        Long count = query().eq("user_id", userid).eq("follow_user_id", followUserId).count();
        return Result.ok(count > 0);
    }

    @Override
    public Result common(Long userId) {
        Long id = UserHolder.getUser().getId();
        Set<String> common = stringRedisTemplate.opsForSet().intersect("follows:"+id.toString(), "follows:"+String.valueOf(userId));
        if(common.isEmpty()||common==null){
            return Result.ok();
        }
        List<Long> collect = common.stream().map(Long::valueOf).collect(Collectors.toList());
        List<UserDTO> users = userService.listByIds(collect).stream().map(user -> BeanUtil.copyProperties(user, UserDTO.class)).collect(Collectors.toList());
        return Result.ok(users);

    }

}
