package com.hmdp.service.impl;

import cn.hutool.Hutool;
import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.bean.copier.CopyOptions;
import cn.hutool.core.date.DateTime;
import cn.hutool.core.lang.UUID;
import cn.hutool.core.util.RandomUtil;
import com.baomidou.mybatisplus.core.toolkit.BeanUtils;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.LoginFormDTO;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.User;
import com.hmdp.mapper.UserMapper;
import com.hmdp.service.IUserService;
import com.hmdp.utils.RegexUtils;
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.connection.BitFieldSubCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import javax.servlet.http.HttpSession;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.*;
import static com.hmdp.utils.SystemConstants.USER_NICK_NAME_PREFIX;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Slf4j
@Service
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements IUserService {

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Override
    public Result sendMsg(String phone, HttpSession session) {
        //手机号不合法
        if(RegexUtils.isPhoneInvalid(phone)){
            return Result.fail("手机号非法");
        }
        //获取code
        String code = RandomUtil.randomNumbers(6);
        log.info("验证码:{}",code);
        //TODO 保存到session
        //存入redis 并且设置一个有效时间
        redisTemplate.opsForValue().set(LOGIN_CODE_KEY+phone,code,LOGIN_CODE_TTL, TimeUnit.MINUTES);
//        session.setAttribute("code",code);
        //设置一个有效时间

        return Result.ok("登录成功");
    }

    @Override
    public Result login(LoginFormDTO phone, HttpSession session) {
        //0.校验手机号
        if(RegexUtils.isPhoneInvalid(phone.getPhone())){
            return Result.fail("手机号非法");
        }

        //1.校样验证码是否存在
        //Object msgCode = session.getAttribute("code");
        //c从token中获取
        String msgCode = redisTemplate.opsForValue().get(LOGIN_CODE_KEY + phone.getPhone());
        System.out.println(msgCode);
        //2.判断手机号是否存在
        if(msgCode == null || !phone.getCode().equals(msgCode)){
            return Result.fail("验证码无效");
        }

        //3.判断用户是否存在
        User user = query().eq("phone", phone.getPhone()).one();

        //4.存在直接返回
        if(user == null){
            //5. 不存在 创建一个新的保存到数据库
           user =  createUser(phone.getPhone());

        }

        //返回一个token作为登录凭证
        String token = UUID.randomUUID().toString(true);

        UserDTO dto = BeanUtil.copyProperties(user,UserDTO.class);
        Map<String, Object> stringObjectMap = BeanUtil.beanToMap(dto,new HashMap<>(),
                CopyOptions.create().setIgnoreNullValue(true).setFieldValueEditor((faildName,faildValue)->faildValue.toString()));
        //把对象存入rides
        redisTemplate.opsForHash().putAll(LOGIN_USER_KEY+token,stringObjectMap);
        //设置过期时间
        redisTemplate.expire(LOGIN_USER_KEY+token,LOGIN_USER_TTL,TimeUnit.MINUTES);
        //把user存入session
        //session.setAttribute("user",dto );
        //设置
        return Result.ok(token);
    }

    @Override
    public Result sign() {
        //获取当前用户
        Long uid = UserHolder.getUser().getId();
        //获取当前时间
        LocalDateTime now = LocalDateTime.now();
        String suffix = now.format(DateTimeFormatter.ofPattern(":YYYYMM"));
        //拼接签到key
        String key = USER_SIGN_KEY + uid + suffix ;
        //获取当前是第几天
        int nowDay = now.getDayOfMonth();
        //签到
        redisTemplate.opsForValue().setBit(key,nowDay-1,true);
        return Result.ok();
    }

    /**
     * 统计本月连续签到次数
     * @return
     */
    @Override
    public Result signCount() {
        //获取当前用户
        Long uid = UserHolder.getUser().getId();
        //获取当前时间
        LocalDateTime now = LocalDateTime.now();
        String suffix = now.format(DateTimeFormatter.ofPattern(":YYYYMM"));
        //拼接签到key
        String key = USER_SIGN_KEY + uid + suffix ;
        //获取当前是第几天
        int nowDay = now.getDayOfMonth();
        //获取本月签到的次数
        List<Long> list = redisTemplate.opsForValue().bitField(key,
                BitFieldSubCommands.create().get
                        (BitFieldSubCommands.BitFieldType.unsigned(nowDay)).valueAt(0));
        //判断是否获取到了
        if(list.isEmpty() || list == null){
            return Result.ok(0);
        }
        //如果获取到了就取出第一个 取出的是一个十进制的数 对他和1进行与运算
        Long num = list.get(0);
        if(num == 0 || num==null){
            return Result.ok(0);
        }
        int count = 0 ;
        //循环与运算 然后位移判断是否签到
        while (true){
            //与1 是 0 就是没签到直接退出循环
            if( (num & 1) == 0){
                break;
            }else{
                count++;
            }
            //循环之后把数字右移
            num >>>= 1;
        }
        return Result.ok(count);
    }

    private User createUser(String phone) {
        User user = new User();
        user.setPhone(phone);
        user.setNickName(USER_NICK_NAME_PREFIX + RandomUtil.randomString(10));

        //添加进数据库
        save(user);
        return user;
    }


}
