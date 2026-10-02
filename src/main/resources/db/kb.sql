-- ============================================================
-- RAG 知识库分片表
-- 一个用户上传/发布的每篇博客，会被切成若干分片存到这里
-- ============================================================

CREATE TABLE IF NOT EXISTS `tb_kb_chunk`
(
    `id`            bigint       NOT NULL AUTO_INCREMENT COMMENT '主键',
    `user_id`       bigint       NOT NULL COMMENT '知识库归属用户id',
    `blog_id`       bigint                DEFAULT NULL COMMENT '来源博客id，文件上传时为NULL',
    `source_type`   varchar(16)  NOT NULL DEFAULT 'BLOG' COMMENT '来源类型：BLOG=用户博客，FILE=上传文件',
    `source_name`   varchar(255)          DEFAULT NULL COMMENT '来源名称：博客标题或文件名',
    `chunk_index`   int          NOT NULL DEFAULT 0 COMMENT '分片在原文中的序号，从0开始',
    `content`       text         NOT NULL COMMENT '分片正文',
    `embedding`     mediumtext            DEFAULT NULL COMMENT '向量，JSON数组文本；为NULL表示未向量化，检索时走关键词兜底',
    `embedding_dim` int                   DEFAULT NULL COMMENT '向量维度',
    `create_time`   timestamp    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (`id`),
    KEY `idx_user` (`user_id`),
    KEY `idx_blog` (`blog_id`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='RAG 知识库分片';
