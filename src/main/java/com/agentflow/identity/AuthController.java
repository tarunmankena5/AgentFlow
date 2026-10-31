package com.agentflow.identity;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController @RequestMapping("/api/v1/auth")
public class AuthController {
    private final AuthService auth;
    public AuthController(AuthService auth) { this.auth=auth; }
    public record RegisterRequest(@Email @NotBlank String email,@Size(min=12,max=72) String password,@NotBlank @Size(max=200) String fullName) { }
    public record LoginRequest(@Email @NotBlank String email,@NotBlank String password) { }
    public record RefreshRequest(@NotBlank String refreshToken) { }
    @PostMapping("/register") @ResponseStatus(HttpStatus.CREATED)
    public AuthService.UserView register(@Valid @RequestBody RegisterRequest body) { return auth.register(body.email(),body.password(),body.fullName()); }
    @PostMapping("/login") public AuthService.Tokens login(@Valid @RequestBody LoginRequest b, jakarta.servlet.http.HttpServletRequest r) { return auth.login(b.email(),b.password(),r.getHeader("User-Agent"),r.getRemoteAddr()); }
    @PostMapping("/refresh") public AuthService.Tokens refresh(@Valid @RequestBody RefreshRequest b,jakarta.servlet.http.HttpServletRequest r) { return auth.refresh(b.refreshToken(),r.getHeader("User-Agent"),r.getRemoteAddr()); }
    @PostMapping("/logout") public Map<String,String> logout(@RequestBody(required=false) RefreshRequest b) { if(b==null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"refreshToken required"); auth.logout(b.refreshToken()); return Map.of("status","logged_out"); }
}
