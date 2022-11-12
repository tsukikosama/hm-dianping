package com.hmdp.service;

import com.hmdp.dto.Result;
import com.hmdp.entity.Blog;
import com.baomidou.mybatisplus.extension.service.IService;

/**
 * <p>
 *  服务类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
public interface IBlogService extends IService<Blog> {
    Result queryHotBlog (Integer current);
    Result queryBlogByid(Long id);

    Result likeBlog(Long id);

    Result getLikes(Long id);

    Result saveBlog(Blog blog);

    Result followUserBlog(Long lastId, Long offset);
}