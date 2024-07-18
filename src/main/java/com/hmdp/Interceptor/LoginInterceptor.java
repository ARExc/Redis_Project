package com.hmdp.Interceptor;

import com.hmdp.dto.UserDTO;
import com.hmdp.utils.UserHolder;
import org.apache.tomcat.jni.User;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.ModelAndView;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;

public class LoginInterceptor implements HandlerInterceptor {
    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        //1.获取cookie->session中的用户
        HttpSession session = request.getSession();
        Object user = session.getAttribute("user");
        //2.判断用户是否存在
        if (user == null) {
            //3.不存在，则拦截
            response.setStatus(401);
            return false;
        }
        //3.存在，则保存用户信息到ThreadLocal中
        UserHolder.saveUser((UserDTO) user);
        //4.放行
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) throws Exception {
        //移除用户，防止内存泄漏
        UserHolder.removeUser();
    }
}
