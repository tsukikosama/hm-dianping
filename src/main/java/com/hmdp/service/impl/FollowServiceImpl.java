package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.stream.CollectorUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Follow;
import com.hmdp.entity.User;
import com.hmdp.mapper.FollowMapper;
import com.hmdp.service.IFollowService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

import static com.hmdp.utils.RedisConstants.FOLLOW_KEY;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Slf4j
@Service
public class FollowServiceImpl extends ServiceImpl<FollowMapper, Follow> implements IFollowService {

    @Autowired
    private StringRedisTemplate template;

    @Autowired
    private UserServiceImpl userService;
    @Override
    public Result follow(Long id, boolean isfollow) {

        System.out.println(isfollow);
        //获取用户信息
        Long userId = UserHolder.getUser().getId();
        //共同关注的key
        String followKey =  FOLLOW_KEY  +  userId;
        //判断是否关注了 关注了就是取消关注 没关注就是关注
        if(!isfollow){
            log.info("取关");
            //已经关注了
            boolean remove = remove(new QueryWrapper<Follow>().eq("user_id", userId).eq("follow_user_id", id));
            //判断是否移除成功了 移除成功从redis中的关注id移除
            if(remove){
                template.opsForSet().remove(followKey,id.toString());
            }

        }else{
            log.info("关注");
            //没有关注
            //添加一个关注
            Follow follow = new Follow();
            follow.setUserId(userId);
            follow.setFollowUserId(id);
            boolean save = save(follow);
            //判断是否保存成功 保存把当前关注的id存入redis
            if(save){
                template.opsForSet().add(followKey,id.toString());
            }

        }
        return Result.ok();
    }

    @Override
    public Result isFollow(Long id) {
        //获取用户信息
        Long userId = UserHolder.getUser().getId() ;
        //判断是否关注
        Long count = query().eq("user_id", userId).eq("follow_user_id", id).count();
        return Result.ok(count > 0);
    }

    @Override
    public Result commonFollow(Long id) {

        //获取当前登录的user的id
        String uid = FOLLOW_KEY +  UserHolder.getUser().getId();
        //判断是否有交集
        Set<String> intersect = template.opsForSet().intersect(uid, FOLLOW_KEY + id);
        if(intersect == null || intersect.isEmpty()){
            return Result.ok(Collections.emptyList());
        }
        //把共同关注的用户集合转成long
        List<Long> ids = intersect.stream().map(Long::valueOf).collect(Collectors.toList());
        //通过id去查询共同关注的用户 然后转成dto对象
        List<UserDTO> dto = userService.listByIds(ids).stream().map(user ->
                BeanUtil.copyProperties(user, UserDTO.class)
        ).collect(Collectors.toList());
        return Result.ok(dto);
    }
}
