package com.hmdp.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.entity.KbChunk;
import com.hmdp.mapper.KbChunkMapper;
import com.hmdp.service.IKbChunkService;
import com.hmdp.service.KnowledgeBaseService;
import org.springframework.stereotype.Service;

@Service
public class KbChunkServiceImpl extends ServiceImpl<KbChunkMapper, KbChunk> implements IKbChunkService {

    @Override
    public void removeByBlogId(Long blogId) {
        if (blogId == null) {
            return;
        }
        remove(new LambdaQueryWrapper<KbChunk>().eq(KbChunk::getBlogId, blogId));
    }

    @Override
    public int removeBlogChunks() {
        // MyBatis-Plus 的 remove 返回 boolean，这里用 mapper 的 delete 拿到影响行数
        return baseMapper.delete(new LambdaQueryWrapper<KbChunk>()
                .eq(KbChunk::getSourceType, KnowledgeBaseService.SOURCE_BLOG));
    }
}
