package com.hmdp.controller;


import com.hmdp.dto.Result;
import com.hmdp.service.IFollowService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

/**
 * <p>
 *  前端控制器
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@RestController
@RequestMapping("/follow")
public class FollowController {

    @Autowired
    private IFollowService service;

    /**
     * 判断是否关注
     * @param id
     * @param isfollow
     * @return
     */
    @PutMapping("/{id}/{isfollow}")
    public Result follow(@PathVariable("id") Long id,@PathVariable("isfollow") boolean isfollow){
        return service.follow(id,isfollow);
    }

    @GetMapping("/or/not/{id}")
    public Result isFollow(@PathVariable("id") Long id){
        return service.isFollow(id);
    }
    /**
     * 共同关注
     */
    @GetMapping("/common/{id}")
    public Result commonFollow(@PathVariable("id") Long id){
        return service.commonFollow(id);
    }
}
