package com.hmdp.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.hmdp.entity.KbChunk;

/**
 * 知识库分片 Mapper。
 * <p>
 * 检索逻辑放在 Service 层用内存计算，这里只需要基础的 CRUD。
 */
public interface KbChunkMapper extends BaseMapper<KbChunk> {

}
