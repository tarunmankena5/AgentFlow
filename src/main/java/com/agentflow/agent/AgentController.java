package com.agentflow.agent;

import com.agentflow.identity.CurrentUser;
import com.agentflow.organization.OrganizationController;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController @RequestMapping("/api/v1/organizations/{organizationId}/agents")
public class AgentController {
    private final JdbcTemplate jdbc;
    public AgentController(JdbcTemplate jdbc) { this.jdbc=jdbc; }
    public record AgentInput(@NotBlank @Size(max=200) String name,@Size(max=4000) String description,@NotBlank @Size(max=20000) String systemPrompt,@NotBlank @Pattern(regexp="(?i)openai",message="Only the openai provider is currently supported") @Size(max=50) String provider,@NotBlank @Size(max=100) String model,@DecimalMin("0.0") @DecimalMax("2.0") BigDecimal temperature,@Min(1) @Max(50) int maxSteps,@Min(1) @Max(100000) int maxTokenBudget,@Min(1) @Max(3600) int timeoutSeconds) { }
    public record Agent(UUID id,String name,String description,String status,Integer version,String model) { }
    @PostMapping @ResponseStatus(HttpStatus.CREATED) @Transactional
    public Agent create(@PathVariable UUID organizationId,@AuthenticationPrincipal CurrentUser user,@Valid @RequestBody AgentInput in) {
        OrganizationController.requireRole(jdbc,organizationId,user.id(),"ORGANIZATION_ADMIN","WORKSPACE_MANAGER","DEVELOPER");
        UUID id=UUID.randomUUID(); jdbc.update("INSERT INTO agents(id,organization_id,name,description,status,created_by) VALUES(?,?,?,?,'DRAFT',?)",id,organizationId,in.name().trim(),in.description(),user.id());
        UUID version=UUID.randomUUID(); jdbc.update("INSERT INTO agent_versions(id,agent_id,organization_id,version_number,system_prompt,llm_provider,model,temperature,max_steps,max_token_budget,timeout_seconds,created_by) VALUES(?,?,?,?,?,?,?,?,?,?,?,?)",version,id,organizationId,1,in.systemPrompt(),in.provider(),in.model(),in.temperature()==null?BigDecimal.ZERO:in.temperature(),in.maxSteps(),in.maxTokenBudget(),in.timeoutSeconds(),user.id());
        jdbc.update("UPDATE agents SET current_version_id=? WHERE id=?",version,id); return new Agent(id,in.name(),in.description(),"DRAFT",1,in.model());
    }
    @GetMapping public List<Agent> list(@PathVariable UUID organizationId,@AuthenticationPrincipal CurrentUser user) {
        OrganizationController.requireRole(jdbc,organizationId,user.id(),"ORGANIZATION_ADMIN","WORKSPACE_MANAGER","DEVELOPER","OPERATOR","VIEWER");
        return jdbc.query("SELECT a.id,a.name,a.description,a.status,v.version_number,v.model FROM agents a LEFT JOIN agent_versions v ON v.id=a.current_version_id WHERE a.organization_id=? ORDER BY a.created_at DESC",(rs,n)->new Agent(rs.getObject(1,UUID.class),rs.getString(2),rs.getString(3),rs.getString(4),(Integer)rs.getObject(5),rs.getString(6)),organizationId);
    }
    @GetMapping("/{agentId}") public Agent get(@PathVariable UUID organizationId,@PathVariable UUID agentId,@AuthenticationPrincipal CurrentUser user) {
        OrganizationController.requireRole(jdbc,organizationId,user.id(),"ORGANIZATION_ADMIN","WORKSPACE_MANAGER","DEVELOPER","OPERATOR","VIEWER");
        return jdbc.query("SELECT a.id,a.name,a.description,a.status,v.version_number,v.model FROM agents a LEFT JOIN agent_versions v ON v.id=a.current_version_id WHERE a.organization_id=? AND a.id=?",(rs,n)->new Agent(rs.getObject(1,UUID.class),rs.getString(2),rs.getString(3),rs.getString(4),(Integer)rs.getObject(5),rs.getString(6)),organizationId,agentId).stream().findFirst().orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND,"Agent not found"));
    }
    @PutMapping("/{agentId}") @Transactional public Agent update(@PathVariable UUID organizationId,@PathVariable UUID agentId,@AuthenticationPrincipal CurrentUser user,@Valid @RequestBody AgentInput in) {
        OrganizationController.requireRole(jdbc,organizationId,user.id(),"ORGANIZATION_ADMIN","WORKSPACE_MANAGER","DEVELOPER");
        var rows=jdbc.query("SELECT current_version_id FROM agents WHERE id=? AND organization_id=? AND status<>'ARCHIVED' FOR UPDATE",(rs,n)->rs.getObject(1,UUID.class),agentId,organizationId);
        if(rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Agent not found");
        UUID old=rows.get(0); Integer next=jdbc.queryForObject("SELECT coalesce(max(version_number),0)+1 FROM agent_versions WHERE agent_id=?",Integer.class,agentId); UUID version=UUID.randomUUID();
        jdbc.update("INSERT INTO agent_versions(id,agent_id,organization_id,version_number,system_prompt,llm_provider,model,temperature,max_steps,max_token_budget,timeout_seconds,created_by) VALUES(?,?,?,?,?,?,?,?,?,?,?,?)",version,agentId,organizationId,next,in.systemPrompt(),in.provider(),in.model(),in.temperature()==null?BigDecimal.ZERO:in.temperature(),in.maxSteps(),in.maxTokenBudget(),in.timeoutSeconds(),user.id());
        if(old!=null) jdbc.update("INSERT INTO agent_version_tools(agent_version_id,tool_id) SELECT ?,tool_id FROM agent_version_tools WHERE agent_version_id=?",version,old);
        jdbc.update("UPDATE agents SET name=?,description=?,status='DRAFT',current_version_id=?,updated_at=now() WHERE id=? AND organization_id=?",in.name().trim(),in.description(),version,agentId,organizationId);
        return new Agent(agentId,in.name().trim(),in.description(),"DRAFT",next,in.model());
    }
    public record ToolAssignment(@NotNull Set<UUID> toolIds) { }
    @PutMapping("/{agentId}/tools") @Transactional public Agent assignTools(@PathVariable UUID organizationId,@PathVariable UUID agentId,@AuthenticationPrincipal CurrentUser user,@Valid @RequestBody ToolAssignment assignment) {
        OrganizationController.requireRole(jdbc,organizationId,user.id(),"ORGANIZATION_ADMIN","WORKSPACE_MANAGER","DEVELOPER");
        var rows=jdbc.query("SELECT a.current_version_id,a.name,a.description FROM agents a WHERE a.id=? AND a.organization_id=? AND a.status<>'ARCHIVED' FOR UPDATE",(rs,n)->new Object[]{rs.getObject(1,UUID.class),rs.getString(2),rs.getString(3)},agentId,organizationId);
        if(rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Agent not found"); Object[] a=rows.get(0); UUID old=(UUID)a[0];
        for(UUID tool:assignment.toolIds()) if(jdbc.queryForObject("SELECT count(*) FROM tools WHERE id=? AND enabled=true AND (organization_id=? OR organization_id IS NULL)",Integer.class,tool,organizationId)==0) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Tool unavailable to this organization");
        Integer next=jdbc.queryForObject("SELECT coalesce(max(version_number),0)+1 FROM agent_versions WHERE agent_id=?",Integer.class,agentId); UUID version=UUID.randomUUID();
        jdbc.update("INSERT INTO agent_versions(id,agent_id,organization_id,version_number,system_prompt,llm_provider,model,temperature,max_steps,max_token_budget,timeout_seconds,memory_config,generation_params,created_by) SELECT ?,agent_id,organization_id,?,system_prompt,llm_provider,model,temperature,max_steps,max_token_budget,timeout_seconds,memory_config,generation_params,? FROM agent_versions WHERE id=?",version,next,user.id(),old);
        for(UUID tool:assignment.toolIds()) jdbc.update("INSERT INTO agent_version_tools(agent_version_id,tool_id) VALUES(?,?)",version,tool);
        jdbc.update("UPDATE agents SET current_version_id=?,status='DRAFT',updated_at=now() WHERE id=?",version,agentId);
        return new Agent(agentId,(String)a[1],(String)a[2],"DRAFT",next,jdbc.queryForObject("SELECT model FROM agent_versions WHERE id=?",String.class,version));
    }
    @PostMapping("/{agentId}/publish") @Transactional public Agent publish(@PathVariable UUID organizationId,@PathVariable UUID agentId,@AuthenticationPrincipal CurrentUser user) {
        OrganizationController.requireRole(jdbc,organizationId,user.id(),"ORGANIZATION_ADMIN","WORKSPACE_MANAGER","DEVELOPER");
        int changed=jdbc.update("UPDATE agents SET status='PUBLISHED',updated_at=now() WHERE id=? AND organization_id=? AND status IN ('DRAFT','PAUSED') AND current_version_id IS NOT NULL",agentId,organizationId);
        if(changed==0) throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Agent not found or not publishable");
        return jdbc.queryForObject("SELECT a.id,a.name,a.description,a.status,v.version_number,v.model FROM agents a JOIN agent_versions v ON v.id=a.current_version_id WHERE a.id=? AND a.organization_id=?",(rs,n)->new Agent(rs.getObject(1,UUID.class),rs.getString(2),rs.getString(3),rs.getString(4),rs.getInt(5),rs.getString(6)),agentId,organizationId);
    }
    @PostMapping("/{agentId}/{action}") @Transactional public Agent changeStatus(@PathVariable UUID organizationId,@PathVariable UUID agentId,@PathVariable String action,@AuthenticationPrincipal CurrentUser user) {
        OrganizationController.requireRole(jdbc,organizationId,user.id(),"ORGANIZATION_ADMIN","WORKSPACE_MANAGER","DEVELOPER");
        String next=switch(action) { case "pause" -> "PAUSED"; case "archive" -> "ARCHIVED"; default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Action must be pause or archive"); };
        int changed=jdbc.update("UPDATE agents SET status=?,updated_at=now() WHERE id=? AND organization_id=? AND status IN ('DRAFT','PUBLISHED','PAUSED')",next,agentId,organizationId);
        if(changed==0) throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Agent not found");
        return jdbc.queryForObject("SELECT a.id,a.name,a.description,a.status,v.version_number,v.model FROM agents a LEFT JOIN agent_versions v ON v.id=a.current_version_id WHERE a.id=? AND a.organization_id=?",(rs,n)->new Agent(rs.getObject(1,UUID.class),rs.getString(2),rs.getString(3),rs.getString(4),(Integer)rs.getObject(5),rs.getString(6)),agentId,organizationId);
    }
}
