package com.hmdp.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.hmdp.entity.KbChunk;

/**
 * 知识库分片服务。
 */
public interface IKbChunkService extends IService<KbChunk> {

    /** 删除某篇博客产生的全部分片 */
    void removeByBlogId(Long blogId);

    /** 删除全站所有「博客来源」的分片，用于重建共享索引前清场 */
    int removeBlogChunks();
}
