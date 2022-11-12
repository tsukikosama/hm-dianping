package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.collection.CollectionUtil;
import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.extension.conditions.query.QueryChainWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hmdp.dto.Result;
import com.hmdp.dto.ScrollResult;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Blog;
import com.hmdp.entity.Follow;
import com.hmdp.entity.User;
import com.hmdp.mapper.BlogMapper;
import com.hmdp.service.IBlogService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.service.IFollowService;
import com.hmdp.service.IUserService;
import com.hmdp.utils.SystemConstants;
import com.hmdp.utils.UserHolder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static com.hmdp.utils.RedisConstants.*;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class BlogServiceImpl extends ServiceImpl<BlogMapper, Blog> implements IBlogService {
    @Resource
    private IUserService userService;

    @Autowired
    private IFollowService followService;

    @Autowired
    private StringRedisTemplate template;
    @Override
    public Result queryHotBlog(Integer current) {
        // 根据用户查询
        Page<Blog> page = query()
                .orderByDesc("liked")
                .page(new Page<>(current, SystemConstants.MAX_PAGE_SIZE));
        // 获取当前页数据
        List<Blog> records = page.getRecords();
        // 查询用户
        records.forEach(blog->{
            this.saveUser(blog);
            this.isLiked(blog);
        });

        return Result.ok(records);
    }

    @Override
    public Result queryBlogByid(Long id) {
        //通过id查询blog
        Blog blog = getById(id);
        //判断blog是否为空
        if (blog == null){
            return Result.fail("博客不存在");
        }
        queryBlogByid(id);
        isLiked(blog);

        return Result.ok(blog);
    }

    private void isLiked(Blog blog) {
       //获取user对象
        UserDTO user = UserHolder.getUser();
        //p判断users是否为空
        if(user == null){
            return;
        }
        //如果user不为空就去获取id
        Long id = user.getId();

        //redis key
        String key = BLOG_LIKED_KEY +blog.getId();

        //判断是否有分
        Double score = template.opsForZSet().score(key,id.toString());
        blog.setIsLike(score != null);
    }

    /**
     * 通过sortset进行点赞的顺序
     * @param id
     * @return
     */
    @Override
    public Result likeBlog(Long id) {
        //获取user对象
        Long userId = UserHolder.getUser().getId();

        String key = BLOG_LIKED_KEY + id;
        //判断是否是set集合的对象
        Double score = template.opsForZSet().score(key, userId.toString());
//        System.out.println(success);
        //如果不是就可以进行点赞
        if(score == null){
            //数据库+1
            boolean isSuccees = update().setSql("liked = liked +1").eq("id", id).update();
            if(isSuccees){
                //把博客的id单座key  用户的id当作值存入redis中
                template.opsForZSet().add(key,userId.toString(),System.currentTimeMillis());
            }
            //如果是就可以进行点赞
        }else{
            //数据库-1
            boolean isSuccees = update().setSql("liked = liked -1").eq("id", id).update();
            if(isSuccees){
                ////把ridis中的的数据移除
                System.out.println(key);
                template.opsForZSet().remove(key,userId.toString());
            }
        }
        return Result.ok();
    }

    /**
     * 通过博客号去查询所有的点赞信息
     * @param id
     * @return
     */
    @Override
    public Result getLikes(Long id) {
        //获取user信息
        Long userId = UserHolder.getUser().getId();
        //判断用户是否为空 如果为空就直接返回空 否者会报错
        if(userId == null){
            return Result.ok(Collections.emptyList());
        }
        //从redis中获取前五个信息
        List<Long> ids = template.opsForZSet().range(BLOG_LIKED_KEY+id, 0, 4)
                .stream().map(Long::valueOf).collect(Collectors.toList());

        //拼接字符串 用于后面的排序
        String uIds = StrUtil.join(",",ids);
//        System.out.println("ids"+uIds);
        //通过id去查询用户信息
        List<UserDTO> users = userService.query().in("id", ids)
                .last("order by field(id," + uIds+")").list()
                .stream().map(user -> BeanUtil.copyProperties(user,UserDTO.class)).collect(Collectors.toList());
        return Result.ok(users);
    }

    @Override
    public Result saveBlog(Blog blog) {
        // 获取登录用户
        UserDTO user = UserHolder.getUser();
        blog.setUserId(user.getId());
        // 保存探店博文
        boolean save = save(blog);
        //判断是否保存成功
        if(!save){
            return Result.fail("添加博客失败");
        }
        //通过当前登录的用户去获取关注她的用户的id
        List<Follow> ids = followService.query().eq("follow_user_id", user.getId()).list();
        //通过通过获取的user id 去推流
        for(Follow item : ids){
            //每个用户都有属于自己的接收箱
            String key = "feed:"+item.getUserId();
            //使用srot_set 来进行推送的排行
            template.opsForZSet().add(key,item.getFollowUserId().toString(),System.currentTimeMillis());
        }
        // 返回id
        return Result.ok("添加成功");
    }

    /**
     * 获取关注用户发布的博客信息
     * @param lastId
     * @param offset
     * @return
     */
    @Override
    public Result followUserBlog(Long lastId, Long offset) {
        //获取当前用户登录的信息
        Long id = UserHolder.getUser().getId();
        //获取关注id的key
        String key = FEED_KEY + id ;
        //System.out.println(key);
        //去redis中查询 reverseRangeByScoreWithScores key max min  offset count
        Set<ZSetOperations.TypedTuple<String>> typedTuples = template.opsForZSet()
                .reverseRangeByScoreWithScores(key, 0, lastId, offset, 2);
        //判断集合是否为空
        if(typedTuples == null || typedTuples.isEmpty()){
            return Result.ok("博客不存在");
        }
        //获取用户id的集合
        ArrayList<Long> ids = new ArrayList<>(typedTuples.size());

        //计入重复分数的个数
        int count = 1;
        //记录最小的那个记录的分数值
        long min = 0;
        //遍历set集合中用户id
        for (ZSetOperations.TypedTuple<String> item : typedTuples){
            //把用户添加进用户集合中
            ids.add(Long.valueOf(item.getValue()));
            //获取用户的分数
            Double score = item.getScore();
            if(score == min){
                //如果分数和最小的分数一直就计数器加1
                count++;
            }else{
                min = Long.valueOf(item.getScore().longValue());
                count = 1;
            }
        }
        //获取到每个用户的id去查询每个博客信息
        String uIds = StrUtil.join(",",ids);
//        System.out.println("ids"+uIds);
        //通过id去查询用户信息
        List<Blog> blogs = query().in
                ("user_id", ids).last("order by field(user_id," + uIds+")").list();
        //每个blog都要获取用户信息
        for(Blog b : blogs){
            queryBlogByid(id);
            isLiked(b);
        }
        //封装成滚动对象
        ScrollResult scrollResult = new ScrollResult();
        scrollResult.setList(blogs);
        scrollResult.setOffset(count);
        scrollResult.setMinTime(min);
        return Result.ok(scrollResult);
    }

    private void saveUser(Blog blog) {
        //查询用户信息
        Long userId = blog.getUserId();
        User user = userService.getById(userId);
        blog.setName(user.getNickName());
        blog.setIcon(user.getIcon());
    }


}
