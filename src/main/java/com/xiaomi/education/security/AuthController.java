package com.xiaomi.education.security;

import com.xiaomi.education.common.ApiException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.session.SessionAuthenticationStrategy;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.CsrfTokenRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthenticationManager authenticationManager;
    private final SecurityContextRepository securityContextRepository;
    private final SessionAuthenticationStrategy sessionAuthenticationStrategy;
    private final CsrfTokenRepository csrfTokenRepository;

    public AuthController(
            AuthenticationManager authenticationManager,
            SecurityContextRepository securityContextRepository,
            SessionAuthenticationStrategy sessionAuthenticationStrategy,
            CsrfTokenRepository csrfTokenRepository
    ) {
        this.authenticationManager = authenticationManager;
        this.securityContextRepository = securityContextRepository;
        this.sessionAuthenticationStrategy = sessionAuthenticationStrategy;
        this.csrfTokenRepository = csrfTokenRepository;
    }

    @GetMapping("/session")
    public AuthSession session(CsrfToken csrfToken, HttpServletResponse response) {
        noStore(response);
        return sessionResponse(SecurityContextHolder.getContext().getAuthentication(), csrfToken);
    }

    @PostMapping("/login")
    public AuthSession login(
            @Valid @RequestBody LoginRequest loginRequest,
            HttpServletRequest request,
            HttpServletResponse response
    ) {
        try {
            var token = UsernamePasswordAuthenticationToken.unauthenticated(
                    loginRequest.email().trim(),
                    loginRequest.password()
            );
            var authentication = authenticationManager.authenticate(token);
            sessionAuthenticationStrategy.onAuthentication(authentication, request, response);

            var context = SecurityContextHolder.createEmptyContext();
            context.setAuthentication(authentication);
            SecurityContextHolder.setContext(context);
            securityContextRepository.saveContext(context, request, response);

            csrfTokenRepository.saveToken(null, request, response);
            var freshCsrfToken = csrfTokenRepository.generateToken(request);
            csrfTokenRepository.saveToken(freshCsrfToken, request, response);

            noStore(response);
            return sessionResponse(authentication, freshCsrfToken);
        } catch (AuthenticationException exception) {
            SecurityContextHolder.clearContext();
            throw new ApiException(
                    HttpStatus.UNAUTHORIZED,
                    "INVALID_CREDENTIALS",
                    "邮箱或密码错误"
            );
        }
    }

    private AuthSession sessionResponse(Authentication authentication, CsrfToken csrfToken) {
        var csrf = new CsrfView(csrfToken.getHeaderName(), csrfToken.getToken());
        if (authentication == null
                || !authentication.isAuthenticated()
                || !(authentication.getPrincipal() instanceof AuthenticatedUser user)) {
            return new AuthSession(false, null, csrf);
        }

        var userView = new UserView(
                user.userId(),
                user.tenantId(),
                user.displayName(),
                user.email(),
                user.role().name()
        );
        return new AuthSession(true, userView, csrf);
    }

    private void noStore(HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store");
        response.setHeader("Pragma", "no-cache");
    }

    public record LoginRequest(
            @NotBlank @Email @Size(max = 256) String email,
            @NotBlank @Size(min = 8, max = 128) String password
    ) {
    }

    public record AuthSession(boolean authenticated, UserView user, CsrfView csrf) {
    }

    public record UserView(
            String id,
            String tenantId,
            String displayName,
            String email,
            String role
    ) {
    }

    public record CsrfView(String headerName, String token) {
    }
}
