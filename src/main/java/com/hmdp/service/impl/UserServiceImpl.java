package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.RandomUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.LoginFormDTO;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.User;
import com.hmdp.mapper.UserMapper;
import com.hmdp.service.IUserService;
import com.hmdp.utils.RegexUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import javax.servlet.http.HttpSession;

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

    @Override
    public Result sendCode(String phone, HttpSession session) {
        //1.校验手机号
        if(RegexUtils.isPhoneInvalid(phone)){
            //2.如果不符合，则返回错误信息
            return Result.fail("手机号格式错误！");
        }
        //3.如果符合，则生成验证码
        String code = RandomUtil.randomNumbers(6);
        //4.将验证码和手机号保存至session域
        session.setAttribute("code",code);
        session.setAttribute("phone",phone);
        //5.发送验证码
        log.debug("验证码为：{}",code);
        //返回ok
        return  Result.ok();
    }

    @Override
    public Result login(LoginFormDTO loginForm, HttpSession session) {
        //1.验证手机号
        String phone = loginForm.getPhone();
        String cachePhone = session.getAttribute("phone").toString();
        if(!phone.equals(cachePhone)){
            return Result.fail("手机号有误！");
        }
        //2.校验验证码
        String cacheCode = session.getAttribute("code").toString();
        String code = loginForm.getCode();
        if(!code.equals(cacheCode)){
            return Result.fail("验证码有误！");
        }
        //3.根据手机号查询用户 select * from tb_user where phone = ?
        User user = query().eq("phone", phone).one();
        //4.判断用户是否存在
        if(user == null){
            //5.不存在则直接自动注册
            user = createUserWithPhone(phone);
        }
        //6.将用户放入session域中
        session.setAttribute("user", BeanUtil.copyProperties(user, UserDTO.class));
        return Result.ok();
    }

    private User createUserWithPhone(String phone) {
        //1.创建用户
        User user = new User();
        user.setPhone(phone);
        user.setNickName("user_"+RandomUtil.randomString(10));
        //2.保存用户至数据库
        save(user);
        return user;
    }
}
