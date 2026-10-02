package com.hmdp.controller;

import cn.hutool.core.io.IoUtil;
import cn.hutool.core.util.StrUtil;
import com.hmdp.config.AiProperties;
import com.hmdp.dto.AiChatDTO;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.service.AiChatService;
import com.hmdp.service.KnowledgeBaseService;
import com.hmdp.utils.UserHolder;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Map;

/**
 * AI 问答与知识库管理。
 * <p>
 * 本控制器不在 MvcConfig 的放行名单里，所以会被 LoginInterceptor 拦住，天然要求登录。
 */
@Slf4j
@RestController
@RequestMapping("/ai")
public class AiController {

    private static final long MAX_UPLOAD_BYTES = 5 * 1024 * 1024L;

    private static final List<String> ALLOWED_SUFFIX = List.of("md", "markdown", "txt", "html", "htm");

    @Resource
    private AiChatService aiChatService;

    @Resource
    private KnowledgeBaseService knowledgeBaseService;

    @Resource
    private AiProperties props;

    /**
     * 核心问答接口：优先用当前用户自己的博客内容来总结回答。
     */
    @PostMapping("/chat")
    public Result chat(@RequestBody AiChatDTO dto) {
        Long userId = currentUserId();
        if (userId == null) {
            return Result.fail("请先登录");
        }
        if (!props.isEnabled()) {
            return Result.fail("AI 功能未启用");
        }
        try {
            return Result.ok(aiChatService.ask(userId, dto));
        } catch (IllegalArgumentException e) {
            return Result.fail(e.getMessage());
        }
    }

    /** 当前用户的知识库概况 */
    @GetMapping("/kb/stats")
    public Result stats() {
        Long userId = currentUserId();
        if (userId == null) {
            return Result.fail("请先登录");
        }
        return Result.ok(knowledgeBaseService.stats(userId));
    }

    /**
     * 重建全站索引：把所有用户的博客重新切分向量化。
     * 知识库是共享的，所以这里重建的是全站数据，而不只是自己的。
     * 刚部署、或换过向量模型之后调一次。
     */
    @PostMapping("/kb/reindex")
    public Result reindex() {
        Long userId = currentUserId();
        if (userId == null) {
            return Result.fail("请先登录");
        }
        int blogs = knowledgeBaseService.reindexAllBlogs();
        return Result.ok(Map.of("blogs", blogs));
    }

    /** 上传 md / txt / html 文件进自己的知识库 */
    @PostMapping("/kb/upload")
    public Result uploadDocument(@RequestParam("file") MultipartFile file,
                                 @RequestParam(value = "title", required = false) String title) {
        Long userId = currentUserId();
        if (userId == null) {
            return Result.fail("请先登录");
        }
        if (file == null || file.isEmpty()) {
            return Result.fail("文件不能为空");
        }
        if (file.getSize() > MAX_UPLOAD_BYTES) {
            return Result.fail("文件太大，最大支持 5MB");
        }

        String originalName = StrUtil.blankToDefault(file.getOriginalFilename(), "");
        String suffix = StrUtil.blankToDefault(StrUtil.subAfter(originalName, ".", true), "").toLowerCase();
        if (!ALLOWED_SUFFIX.contains(suffix)) {
            return Result.fail("只支持 md / markdown / txt / html 文件");
        }

        String text;
        try (InputStream in = file.getInputStream()) {
            text = IoUtil.readUtf8(in);
        } catch (IOException e) {
            log.error("读取上传文件失败 name={}", originalName, e);
            return Result.fail("文件读取失败");
        }

        if (suffix.startsWith("htm")) {
            text = stripHtml(text);
        }
        if (StrUtil.isBlank(text)) {
            return Result.fail("文件内容为空");
        }

        String sourceName = StrUtil.blankToDefault(title, originalName);
        int chunks = knowledgeBaseService.indexDocument(
                userId, null, KnowledgeBaseService.SOURCE_FILE, sourceName, text);
        log.info("知识库新增文件 userId={} name={} 分片数={}", userId, sourceName, chunks);
        return Result.ok(Map.of("name", sourceName, "chunks", chunks));
    }

    /** 删除某篇博客的索引（只能删自己的） */
    @DeleteMapping("/kb/blog/{blogId}")
    public Result removeBlogIndex(@PathVariable("blogId") Long blogId) {
        Long userId = currentUserId();
        if (userId == null) {
            return Result.fail("请先登录");
        }
        return knowledgeBaseService.removeBlogIndex(blogId, userId)
                ? Result.ok()
                : Result.fail("没有找到该博客的索引");
    }

    private Long currentUserId() {
        UserDTO user = UserHolder.getUser();
        return user == null ? null : user.getId();
    }

    /** 粗暴剥掉 HTML 标签，够用就行 */
    private static String stripHtml(String html) {
        return html.replaceAll("(?is)<script.*?</script>", " ")
                .replaceAll("(?is)<style.*?</style>", " ")
                .replaceAll("(?s)<[^>]+>", " ")
                .replace("&nbsp;", " ")
                .replace("&amp;", "&")
                .replace("&lt;", "<")
                .replace("&gt;", ">");
    }
}
