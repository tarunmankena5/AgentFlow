package com.agentflow.organization;

import com.agentflow.identity.CurrentUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController @RequestMapping("/api/v1/organizations")
public class OrganizationController {
    private final JdbcTemplate jdbc;
    public OrganizationController(JdbcTemplate jdbc) { this.jdbc=jdbc; }
    public record Create(@NotBlank @Size(max=200) String name) { }
    public record Organization(UUID id,String name,String slug,String role) { }
    @PostMapping @ResponseStatus(HttpStatus.CREATED) @Transactional
    public Organization create(@Valid @RequestBody Create body,@AuthenticationPrincipal CurrentUser user) {
        String slug=body.name().trim().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+","-").replaceAll("(^-|-$)","");
        if(slug.isBlank()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Organization name must contain letters or numbers");
        UUID id=UUID.randomUUID();
        try { jdbc.update("INSERT INTO organizations(id,name,slug) VALUES(?,?,?)",id,body.name().trim(),slug); }
        catch(org.springframework.dao.DuplicateKeyException e) { throw new ResponseStatusException(HttpStatus.CONFLICT,"Organization slug already exists"); }
        jdbc.update("INSERT INTO organization_memberships(organization_id,user_id,role_id) SELECT ?,?,id FROM roles WHERE name='ORGANIZATION_ADMIN'",id,user.id());
        return new Organization(id,body.name().trim(),slug,"ORGANIZATION_ADMIN");
    }
    @GetMapping public List<Organization> list(@AuthenticationPrincipal CurrentUser user) {
        return jdbc.query("SELECT o.id,o.name,o.slug,r.name FROM organizations o JOIN organization_memberships m ON m.organization_id=o.id JOIN roles r ON r.id=m.role_id WHERE m.user_id=? AND m.active=true ORDER BY o.name",(rs,n)->new Organization(rs.getObject(1,UUID.class),rs.getString(2),rs.getString(3),rs.getString(4)),user.id());
    }
    public static String requireRole(JdbcTemplate jdbc, UUID organization, UUID user, String... allowed) {
        String marks=String.join(",",java.util.Collections.nCopies(allowed.length,"?")); Object[] args=new Object[allowed.length+2]; args[0]=organization; args[1]=user; System.arraycopy(allowed,0,args,2,allowed.length);
        var roles=jdbc.query("SELECT r.name FROM organization_memberships m JOIN roles r ON r.id=m.role_id JOIN organizations o ON o.id=m.organization_id WHERE m.organization_id=? AND m.user_id=? AND m.active=true AND o.active=true AND r.name IN ("+marks+")",(rs,n)->rs.getString(1),args);
        if(roles.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Organization not found"); return roles.get(0);
    }
}
