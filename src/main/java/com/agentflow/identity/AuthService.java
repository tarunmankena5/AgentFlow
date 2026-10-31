package com.agentflow.identity;

import com.agentflow.shared.config.AgentFlowProperties;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class AuthService {
    private final JdbcTemplate jdbc; private final PasswordEncoder passwords; private final JwtService jwt; private final AgentFlowProperties props;
    private final SecureRandom random = new SecureRandom();
    public AuthService(JdbcTemplate jdbc, PasswordEncoder passwords, JwtService jwt, AgentFlowProperties props) { this.jdbc=jdbc; this.passwords=passwords; this.jwt=jwt; this.props=props; }
    public record Tokens(String accessToken, String refreshToken, String tokenType, Instant expiresAt) { }
    public record UserView(UUID id, String email, String fullName, boolean emailVerified) { }
    @Transactional public UserView register(String email, String password, String fullName) {
        String normalized = email.trim().toLowerCase(java.util.Locale.ROOT);
        if (!normalized.matches("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$")) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid email");
        if (password.length()<12 || password.getBytes(StandardCharsets.UTF_8).length>72) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Password must be at least 12 characters and no more than 72 UTF-8 bytes");
        UUID id=UUID.randomUUID();
        try { jdbc.update("INSERT INTO users(id,email,password_hash,full_name) VALUES (?,?,?,?)",id,normalized,passwords.encode(password),fullName.trim()); }
        catch (DuplicateKeyException e) { throw new ResponseStatusException(HttpStatus.CONFLICT,"Email already registered"); }
        return new UserView(id,normalized,fullName.trim(),false);
    }
    @Transactional(noRollbackFor = ResponseStatusException.class) public Tokens login(String email, String password, String userAgent, String ip) {
        var rows=jdbc.query("SELECT id,email,password_hash,active,super_admin,failed_logins,locked_until FROM users WHERE lower(email)=lower(?) FOR UPDATE",(rs,n)->new Object[]{rs.getObject(1,UUID.class),rs.getString(2),rs.getString(3),rs.getBoolean(4),rs.getBoolean(5),rs.getInt(6),rs.getTimestamp(7)},email);
        if(rows.isEmpty()) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,"Invalid credentials");
        Object[] r=rows.get(0); UUID id=(UUID)r[0]; String normalized=(String)r[1];
        if (!Boolean.TRUE.equals(r[3])) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,"Invalid credentials");
        if(r[6]!=null&&((java.sql.Timestamp)r[6]).toInstant().isAfter(Instant.now())) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,"Invalid credentials");
        boolean matches=false; try { matches=password!=null&&password.getBytes(StandardCharsets.UTF_8).length<=72&&passwords.matches(password,(String)r[2]); } catch(IllegalArgumentException ignored) { }
        if(!matches){jdbc.update("UPDATE users SET failed_logins=failed_logins+1,locked_until=CASE WHEN failed_logins+1>=5 THEN now()+interval '15 minutes' ELSE locked_until END,updated_at=now() WHERE id=?",id);throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,"Invalid credentials");}
        jdbc.update("UPDATE users SET failed_logins=0,locked_until=NULL,updated_at=now() WHERE id=?",id);
        return issue(id,normalized,(Boolean)r[4],userAgent,ip);
    }
    private Tokens issue(UUID id,String email,boolean admin,String ua,String ip) {
        byte[] bytes=new byte[48]; random.nextBytes(bytes); String raw=Base64.getUrlEncoder().withoutPadding().encodeToString(bytes); UUID family=UUID.randomUUID();
        Instant expiry=Instant.now().plus(props.security().refreshTokenTtl());
        jdbc.update("INSERT INTO refresh_tokens(id,user_id,family_id,token_hash,expires_at,user_agent,ip_address) VALUES(?,?,?,?,?,?,?)",UUID.randomUUID(),id,family,hash(raw),java.sql.Timestamp.from(expiry),truncate(ua,300),truncate(ip,45));
        return new Tokens(jwt.issue(id,email,admin),raw,"Bearer",Instant.now().plus(props.security().accessTokenTtl()));
    }
    // A replay revokes the token family and then returns 401; commit that revocation despite the response exception.
    @Transactional(noRollbackFor = ResponseStatusException.class) public Tokens refresh(String token,String ua,String ip) {
        var rows=jdbc.query("SELECT id,user_id,family_id,expires_at,revoked_at FROM refresh_tokens WHERE token_hash=? FOR UPDATE",(rs,n)->new Object[]{rs.getObject(1,UUID.class),rs.getObject(2,UUID.class),rs.getObject(3,UUID.class),rs.getTimestamp(4).toInstant(),rs.getTimestamp(5)},hash(token));
        if(rows.isEmpty()) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,"Invalid refresh token"); Object[] r=rows.get(0); UUID old=(UUID)r[0], user=(UUID)r[1], family=(UUID)r[2];
        if (r[4]!=null) { jdbc.update("UPDATE refresh_tokens SET revoked_at=now() WHERE family_id=? AND revoked_at IS NULL",family); throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,"Refresh token reuse detected"); }
        if (((Instant)r[3]).isBefore(Instant.now())) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,"Refresh token expired");
        var u=jdbc.queryForMap("SELECT email,super_admin,active FROM users WHERE id=?",user);
        if (!Boolean.TRUE.equals(u.get("active"))) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,"Account inactive");
        byte[] bytes=new byte[48]; random.nextBytes(bytes); String raw=Base64.getUrlEncoder().withoutPadding().encodeToString(bytes); UUID next=UUID.randomUUID(); Instant expiry=Instant.now().plus(props.security().refreshTokenTtl());
        jdbc.update("INSERT INTO refresh_tokens(id,user_id,family_id,token_hash,expires_at,user_agent,ip_address) VALUES(?,?,?,?,?,?,?)",next,user,family,hash(raw),java.sql.Timestamp.from(expiry),truncate(ua,300),truncate(ip,45));
        jdbc.update("UPDATE refresh_tokens SET revoked_at=now(),replaced_by_id=? WHERE id=?",next,old);
        return new Tokens(jwt.issue(user,(String)u.get("email"),(Boolean)u.get("super_admin")),raw,"Bearer",Instant.now().plus(props.security().accessTokenTtl()));
    }
    @Transactional public void logout(String token) { jdbc.update("UPDATE refresh_tokens SET revoked_at=now() WHERE token_hash=? AND revoked_at IS NULL",hash(token)); }
    private static String hash(String raw) { try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(raw.getBytes(StandardCharsets.UTF_8))); } catch(Exception e){throw new IllegalStateException(e);} }
    private static String truncate(String s,int n) { return s==null?null:s.substring(0,Math.min(s.length(),n)); }
}
