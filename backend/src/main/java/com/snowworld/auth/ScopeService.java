package com.snowworld.auth;

import com.snowworld.common.ApiException;
import com.snowworld.common.ErrorCode;
import java.util.Collection;
import java.util.List;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

/**
 * 景区数据范围判定。每次调用都从数据库读取, 保证授权变更与账号停用即时生效;
 * 所有按景区访问的业务查询、文件、视频、Agent 工具和 SSE 都必须经过这里。
 */
@Service
public class ScopeService {

    private final UserMapper userMapper;

    public ScopeService(UserMapper userMapper) {
        this.userMapper = userMapper;
    }

    public static AuthUser currentUser() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !(auth.getPrincipal() instanceof AuthUser user)) {
            throw new ApiException(ErrorCode.UNAUTHENTICATED);
        }
        return user;
    }

    public List<Long> allowedSiteIds() {
        return allowedSiteIds(currentUser().getId());
    }

    public List<Long> allowedSiteIds(long userId) {
        return userMapper.findAllowedSiteIds(userId);
    }

    public void requireSite(long siteId) {
        requireSite(currentUser().getId(), siteId);
    }

    public void requireSite(long userId, long siteId) {
        if (!allowedSiteIds(userId).contains(siteId)) {
            throw new ApiException(ErrorCode.SITE_FORBIDDEN);
        }
    }

    /** 请求指定景区时必须全部在授权范围内; 未指定时返回全部授权景区。 */
    public List<Long> resolve(Collection<Long> requested) {
        return resolve(currentUser().getId(), requested);
    }

    public List<Long> resolve(long userId, Collection<Long> requested) {
        List<Long> allowed = allowedSiteIds(userId);
        if (requested == null || requested.isEmpty()) {
            return allowed;
        }
        for (Long id : requested) {
            if (!allowed.contains(id)) {
                throw new ApiException(ErrorCode.SITE_FORBIDDEN);
            }
        }
        return List.copyOf(requested);
    }

    public void requirePermission(Permission permission) {
        if (!currentUser().has(permission)) {
            throw new ApiException(ErrorCode.FORBIDDEN);
        }
    }
}
