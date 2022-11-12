package com.hmdp.utils;


import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.StrUtil;
import com.hmdp.dto.UserDTO;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.servlet.HandlerInterceptor;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.LOGIN_USER_KEY;
import static com.hmdp.utils.RedisConstants.LOGIN_USER_TTL;


public class RefreashInterceptor implements HandlerInterceptor {
    private StringRedisTemplate redisTemplate;

    public RefreashInterceptor(StringRedisTemplate r){
         this.redisTemplate = r;
    }
    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        //判断请求头中是否有token
        String token = request.getHeader("authorization");
//        System.out.println(token);
        //判断token是否存在 如果是401就放行 给下一个拦截器
        if(StrUtil.isBlank(token)){
//            response.setStatus(401);
//            System.out.println("fx");
            return true;
        }
        //获取session
        //Object user = request.getSession().getAttribute("user");
        String key = LOGIN_USER_KEY+token;
        // 2.基于TOKEN获取redis中的用户
        Map<Object, Object> entries = redisTemplate.opsForHash().entries(key);

        //判断用户是否存在
        if (entries.isEmpty()){
//            response.setStatus(401);
            return true;
        }
        //封装成一个对象
        UserDTO user =BeanUtil.fillBeanWithMap(entries,new UserDTO(),false);

        // 6.存在，保存用户信息到 ThreadLocal
        UserHolder.saveUser(user);
        //刷新失效时间
        redisTemplate.expire(LOGIN_USER_KEY+token,LOGIN_USER_TTL, TimeUnit.MINUTES);
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) throws Exception {
//        HandlerInterceptor.super.afterCompletion(request, response, handler, ex);

        //销毁
        UserHolder.removeUser();
    }
}
