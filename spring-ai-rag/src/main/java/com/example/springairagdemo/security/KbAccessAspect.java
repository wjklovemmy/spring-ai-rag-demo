package com.example.springairagdemo.security;

import com.example.springairagdemo.service.KbAuthorizationService;
import com.example.user.security.ForbiddenException;
import lombok.RequiredArgsConstructor;
import org.aspectj.lang.JoinPoint;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Before;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.stereotype.Component;

import java.lang.reflect.Parameter;
import java.util.Map;

/**
 * 知识库访问控制切面：
 * 拦截所有标注 {@link RequireKbRole} 的 Controller 方法，在进入业务逻辑前完成数据权限校验。
 * 校验失败抛出 {@link ForbiddenException}，由全局异常处理器转为 403。
 */
@Aspect
@Component
@RequiredArgsConstructor
public class KbAccessAspect {

    private final KbAuthorizationService authorizationService;

    @Before("@annotation(requireKbRole)")
    public void checkAccess(JoinPoint joinPoint, RequireKbRole requireKbRole) {
        Long kbId = resolveKbId(joinPoint, requireKbRole.kbParam());
        if (kbId == null) {
            throw new ForbiddenException("缺少知识库 ID（参数 " + requireKbRole.kbParam() + "），无法校验权限");
        }
        authorizationService.assertRole(kbId, requireKbRole.value());
    }

    /**
     * 从方法参数中解析知识库 ID，优先级：
     * 1. 参数名与 kbParam 匹配的 Number 参数
     * 2. 唯一的一个 Number 参数
     * 3. Map 类型参数中 kbParam 对应 key
     */
    private Long resolveKbId(JoinPoint joinPoint, String kbParam) {
        Object[] args = joinPoint.getArgs();
        MethodSignature signature = (MethodSignature) joinPoint.getSignature();
        Parameter[] parameters = signature.getMethod().getParameters();

        // 1. 参数名匹配（-parameters 编译参数已开启）
        for (int i = 0; i < parameters.length && i < args.length; i++) {
            if (kbParam.equalsIgnoreCase(parameters[i].getName())) {
                Long id = toLong(args[i]);
                if (id != null) {
                    return id;
                }
            }
        }

        // 2. 唯一 Number 参数
        Long numberOnly = null;
        for (Object arg : args) {
            if (arg instanceof Number number) {
                if (numberOnly != null) {
                    numberOnly = null; // 多个 Number，无法唯一确定
                    break;
                }
                numberOnly = number.longValue();
            }
        }
        if (numberOnly != null) {
            return numberOnly;
        }

        // 3. Map body
        for (Object arg : args) {
            if (arg instanceof Map<?, ?> map) {
                Long id = toLong(map.get(kbParam));
                if (id != null) {
                    return id;
                }
            }
        }
        return null;
    }

    private Long toLong(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value instanceof CharSequence text) {
            try {
                return Long.parseLong(text.toString().trim());
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }
}
