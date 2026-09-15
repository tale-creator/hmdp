package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Blog;
import com.hmdp.entity.User;
import com.hmdp.mapper.BlogMapper;
import com.hmdp.service.IBlogService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.service.IUserService;
import com.hmdp.utils.SystemConstants;
import com.hmdp.utils.UserHolder;
import jakarta.annotation.Resource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class BlogServiceImpl extends ServiceImpl<BlogMapper, Blog> implements IBlogService {

    @Resource
    private IUserService userService;
    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public Result queryBlogById(Long id) {
        Blog blog = getById(id);
        if(blog == null){
            return Result.fail("博客不存在");
        }
        // 获取博客作者ID
        Long userId = blog.getUserId();
        // 修正：查询作者信息必须用 userId
        User user = userService.getById(userId);
        blog.setIcon(user.getIcon());
        blog.setName(user.getNickName());

        // 获取当前看博客的用户ID
        UserDTO currentUser = UserHolder.getUser();
        if(currentUser != null) {
            // 判断当前用户是否点赞了这篇博客（Key应该是 like:blogId）
            Double score = stringRedisTemplate.opsForZSet().score("like:" + id, currentUser.getId().toString());
            boolean ismember = BooleanUtil.isTrue(score != null);
            blog.setIsLike(ismember);
        }

        return Result.ok(blog);
    }

    @Override
    public Result likeBlog(Long id) {
        Long userId = UserHolder.getUser().getId();

        // 修正：判断用户是否点赞过这篇博客，Key是 like:blogId，value是 userId
        Double isliked = stringRedisTemplate.opsForZSet().score("like:" + id, userId.toString());
        if(isliked == null){
            boolean update = update().setSql("liked = liked + 1").eq("id", id).update();
            if(update){
                stringRedisTemplate.opsForZSet().add("like:" + id, userId.toString(), System.currentTimeMillis());
            }
            return Result.ok();
        }else{
            boolean remove = update().setSql("liked = liked - 1").eq("id", id).update();
            if(remove){
                stringRedisTemplate.opsForZSet().remove("like:" + id, userId.toString());
            }
            return Result.ok("取消点赞");
        }
    }

    @Override
    public Result queryHotBlog(Integer current) {
        Page<Blog> page = query()
                .orderByDesc("liked")
                .page(new Page<>(current, SystemConstants.MAX_PAGE_SIZE));

        List<Blog> records = page.getRecords();
        UserDTO currentUser = UserHolder.getUser();

        records.forEach(blog ->{
            Long blogUserId = blog.getUserId();
            User user = userService.getById(blogUserId);
            blog.setName(user.getNickName());
            blog.setIcon(user.getIcon());

            // 判断是否点亮：用“当前看文章的人”去查，Key是 like:blogId
            if(currentUser != null) {
                Double score = stringRedisTemplate.opsForZSet().score("like:" + blog.getId(), currentUser.getId().toString());
                boolean ismember = BooleanUtil.isTrue(score != null);
                blog.setIsLike(ismember);
            }
        });
        return Result.ok(records);
    }

    @Override
    public Result queryBlogLikes(Long id) {
        // 修正：这里传入的 id 就是 blogId，Key 是 like:blogId
        Set<String> set = stringRedisTemplate.opsForZSet().range("like:" + id, 0, 4);
        List<Long> list = null;
        if (set != null) {
            list = set.stream().map(Long::valueOf).collect(Collectors.toList());
        }
        // 处理 list 为 null 的情况
        if(list == null || list.isEmpty()){
            return Result.ok(java.util.Collections.emptyList());
        }

        String ids = StrUtil.join(",", list);
        List<UserDTO> users = userService.query().in("id", list)
                .last("order by field(id, " + ids + ")")
                .list().stream()
                .map(user -> BeanUtil.copyProperties(user, UserDTO.class))
                .collect(Collectors.toList());
        return Result.ok(users);
    }
}

