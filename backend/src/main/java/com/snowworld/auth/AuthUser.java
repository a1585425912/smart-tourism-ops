package com.snowworld.auth;

import java.io.Serial;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

/** 会话中保存的登录用户。景区范围不缓存在会话里, 每次访问由 ScopeService 实时读取。 */
public class AuthUser implements UserDetails {

    @Serial
    private static final long serialVersionUID = 1L;

    private final long id;
    private final String username;
    private final String displayName;
    private final String passwordHash;
    private final boolean enabled;
    private final List<String> roles;
    private final Set<Permission> permissions;

    public AuthUser(long id, String username, String displayName, String passwordHash, boolean enabled, List<String> roles) {
        this.id = id;
        this.username = username;
        this.displayName = displayName;
        this.passwordHash = passwordHash;
        this.enabled = enabled;
        this.roles = List.copyOf(roles);
        this.permissions = Permission.forRoles(roles);
    }

    public long getId() {
        return id;
    }

    public String getDisplayName() {
        return displayName;
    }

    public List<String> getRoles() {
        return roles;
    }

    public Set<Permission> getPermissions() {
        return permissions;
    }

    public boolean has(Permission permission) {
        return permissions.contains(permission);
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        List<GrantedAuthority> out = new ArrayList<>();
        roles.forEach(r -> out.add(new SimpleGrantedAuthority("ROLE_" + r)));
        permissions.forEach(p -> out.add(new SimpleGrantedAuthority(p.name())));
        return out;
    }

    @Override
    public String getPassword() {
        return passwordHash;
    }

    @Override
    public String getUsername() {
        return username;
    }

    @Override
    public boolean isEnabled() {
        return enabled;
    }

    /** 会话中不保留密码散列。 */
    public AuthUser withoutPassword() {
        return new AuthUser(id, username, displayName, "", enabled, roles);
    }
}
