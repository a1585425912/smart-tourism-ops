package com.snowworld.auth;

import com.snowworld.audit.AuditService;
import com.snowworld.common.ApiException;
import com.snowworld.common.ErrorCode;
import com.snowworld.scenic.SiteMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Map;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private final AuthenticationManager authenticationManager;
    private final SecurityContextRepository contextRepository;
    private final ScopeService scopeService;
    private final SiteMapper siteMapper;
    private final AuditService audit;

    public AuthController(AuthenticationManager authenticationManager, SecurityContextRepository contextRepository,
                          ScopeService scopeService, SiteMapper siteMapper, AuditService audit) {
        this.authenticationManager = authenticationManager;
        this.contextRepository = contextRepository;
        this.scopeService = scopeService;
        this.siteMapper = siteMapper;
        this.audit = audit;
    }

    public record LoginRequest(@NotBlank @Size(max = 64) String username, @NotBlank @Size(max = 128) String password) {
    }

    public record SiteBrief(long id, String code, String name, String shortName) {
    }

    public record MeResponse(long id, String username, String displayName, List<String> roles,
                             List<String> permissions, List<SiteBrief> sites) {
    }

    /** 触发 CSRF Cookie 下发, 前端在首次写请求前调用。 */
    @GetMapping("/csrf")
    public Map<String, String> csrf(CsrfToken token) {
        return Map.of("headerName", token.getHeaderName(), "token", token.getToken());
    }

    @PostMapping("/login")
    public MeResponse login(@Valid @RequestBody LoginRequest req, HttpServletRequest request, HttpServletResponse response) {
        Authentication auth;
        try {
            auth = authenticationManager.authenticate(
                    UsernamePasswordAuthenticationToken.unauthenticated(req.username(), req.password()));
        } catch (AuthenticationException e) {
            audit.record(null, req.username(), "LOGIN", "USER", null, null, "FAILED", e.getClass().getSimpleName());
            throw new ApiException(ErrorCode.BAD_CREDENTIALS);
        }
        AuthUser user = ((AuthUser) auth.getPrincipal()).withoutPassword();
        HttpSession old = request.getSession(false);
        if (old != null) {
            request.changeSessionId();
        }
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(user, null, user.getAuthorities()));
        SecurityContextHolder.setContext(context);
        contextRepository.saveContext(context, request, response);
        audit.record(user.getId(), user.getUsername(), "LOGIN", "USER", String.valueOf(user.getId()), null, "OK", null);
        return me();
    }

    @PostMapping("/logout")
    public Map<String, Boolean> logout(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session != null) {
            session.invalidate();
        }
        SecurityContextHolder.clearContext();
        return Map.of("ok", true);
    }

    @GetMapping("/me")
    public MeResponse me() {
        AuthUser user = ScopeService.currentUser();
        List<Long> allowed = scopeService.allowedSiteIds(user.getId());
        List<SiteBrief> sites = siteMapper.findAll().stream()
                .filter(s -> allowed.contains(s.getId()))
                .map(s -> new SiteBrief(s.getId(), s.getCode(), s.getName(), s.getShortName()))
                .toList();
        return new MeResponse(user.getId(), user.getUsername(), user.getDisplayName(), user.getRoles(),
                user.getPermissions().stream().map(Enum::name).toList(), sites);
    }
}
