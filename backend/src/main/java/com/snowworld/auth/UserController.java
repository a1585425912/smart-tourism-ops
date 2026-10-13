package com.snowworld.auth;

import com.snowworld.audit.AuditService;
import com.snowworld.common.ApiException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Set;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 用户、角色与景区授权管理, 仅集团管理员可用。 */
@RestController
@RequestMapping("/api/v1/users")
@PreAuthorize("hasAuthority('USER_MANAGE')")
public class UserController {

    private static final Set<String> ROLES = Set.of("GROUP_ADMIN", "SITE_ADMIN", "OPERATOR", "INSPECTOR", "REVIEWER", "VIEWER");

    private final UserMapper userMapper;
    private final PasswordEncoder encoder;
    private final AuditService audit;

    public UserController(UserMapper userMapper, PasswordEncoder encoder, AuditService audit) {
        this.userMapper = userMapper;
        this.encoder = encoder;
        this.audit = audit;
    }

    public record UserView(long id, String username, String displayName, String status, boolean allSites,
                           List<String> roles, List<Long> siteIds) {
    }

    public record CreateUser(@NotBlank @Pattern(regexp = "[a-zA-Z0-9_]{3,32}") String username,
                             @NotBlank @Size(max = 32) String displayName,
                             @NotBlank @Size(min = 10, max = 64) String password,
                             @NotNull List<String> roles, boolean allSites, List<Long> siteIds) {
    }

    public record UpdateUser(@NotBlank @Size(max = 32) String displayName, @NotBlank String status,
                             @NotNull List<String> roles, boolean allSites, List<Long> siteIds,
                             @Size(min = 10, max = 64) String password) {
    }

    @GetMapping
    public List<UserView> list() {
        return userMapper.findAll().stream().map(this::view).toList();
    }

    @PostMapping
    @Transactional
    public UserView create(@Valid @RequestBody CreateUser req) {
        validateRoles(req.roles());
        UserMapper.UserRow row = new UserMapper.UserRow();
        row.setUsername(req.username());
        row.setDisplayName(req.displayName());
        row.setPasswordHash(encoder.encode(req.password()));
        row.setAllSites(req.allSites());
        userMapper.insert(row);
        applyRolesAndScope(row.getId(), req.roles(), req.siteIds());
        audit.record("USER_CREATE", "USER", String.valueOf(row.getId()), null, "OK", req.username());
        return view(userMapper.findById(row.getId()));
    }

    @PatchMapping("/{id}")
    @Transactional
    public UserView update(@PathVariable long id, @Valid @RequestBody UpdateUser req) {
        validateRoles(req.roles());
        if (!Set.of("ACTIVE", "DISABLED").contains(req.status())) {
            throw ApiException.badRequest("status 只能为 ACTIVE 或 DISABLED");
        }
        UserMapper.UserRow row = userMapper.findById(id);
        if (row == null) {
            throw ApiException.notFound("用户");
        }
        if (id == ScopeService.currentUser().getId() && !"ACTIVE".equals(req.status())) {
            throw ApiException.badRequest("不能停用当前登录账号");
        }
        row.setDisplayName(req.displayName());
        row.setStatus(req.status());
        row.setAllSites(req.allSites());
        userMapper.update(row);
        if (req.password() != null) {
            userMapper.updatePassword(id, encoder.encode(req.password()));
        }
        applyRolesAndScope(id, req.roles(), req.siteIds());
        audit.record("USER_UPDATE", "USER", String.valueOf(id), null, "OK", "roles=" + req.roles());
        return view(userMapper.findById(id));
    }

    private void applyRolesAndScope(long userId, List<String> roles, List<Long> siteIds) {
        userMapper.clearRoles(userId);
        roles.stream().distinct().forEach(r -> userMapper.addRole(userId, r));
        userMapper.clearScope(userId);
        if (siteIds != null) {
            siteIds.stream().distinct().forEach(s -> userMapper.addScope(userId, s));
        }
    }

    private static void validateRoles(List<String> roles) {
        if (roles.isEmpty() || !ROLES.containsAll(roles)) {
            throw ApiException.badRequest("角色不合法");
        }
    }

    private UserView view(UserMapper.UserRow row) {
        return new UserView(row.getId(), row.getUsername(), row.getDisplayName(), row.getStatus(), row.isAllSites(),
                userMapper.findRoles(row.getId()), userMapper.findExplicitScope(row.getId()));
    }
}
