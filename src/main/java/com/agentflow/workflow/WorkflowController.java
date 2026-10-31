package com.agentflow.workflow;

import com.agentflow.identity.CurrentUser;
import com.agentflow.organization.OrganizationController;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

/** Tenant-scoped workflow definitions with immutable published versions and DAG validation. */
@RestController @RequestMapping("/api/v1/organizations/{organizationId}/workflows")
public class WorkflowController {
    private final JdbcTemplate jdbc; private final ObjectMapper json;
    public WorkflowController(JdbcTemplate jdbc,ObjectMapper json) { this.jdbc=jdbc; this.json=json; }
    public record Step(@NotBlank @Size(max=100) String key,@NotBlank String type,UUID agentVersionId,UUID toolId,Map<String,Object> config,List<String> dependsOn,@Min(1) @Max(3600) int timeoutSeconds,@Min(1) @Max(10) int retryMaxAttempts,boolean requiresApproval) { }
    public record Definition(@NotBlank @Size(max=200) String name,@Size(max=4000) String description,@NotBlank String triggerType,@Min(1) @Max(86400) int timeoutSeconds,Map<String,Object> inputSchema,Map<String,Object> outputMapping,@NotEmpty @Size(max=100) List<@Valid Step> steps) { }
    public record Workflow(UUID id,String name,String description,String triggerType,String status,Integer version) { }
    @PostMapping @ResponseStatus(HttpStatus.CREATED) @Transactional
    public Workflow create(@PathVariable UUID organizationId,@AuthenticationPrincipal CurrentUser user,@Valid @RequestBody Definition d) {
        authorize(organizationId,user,"ORGANIZATION_ADMIN","WORKSPACE_MANAGER","DEVELOPER"); validate(d,organizationId);
        UUID id=UUID.randomUUID();
        try { jdbc.update("INSERT INTO workflows(id,organization_id,name,description,trigger_type,status,created_by) VALUES(?,?,?,?,?,'DRAFT',?)",id,organizationId,d.name().trim(),d.description(),d.triggerType().toUpperCase(Locale.ROOT),user.id()); }
        catch(org.springframework.dao.DuplicateKeyException ex) { throw new ResponseStatusException(HttpStatus.CONFLICT,"Workflow name already exists"); }
        UUID version=UUID.randomUUID(); jdbc.update("INSERT INTO workflow_versions(id,workflow_id,organization_id,version_number,input_schema,output_mapping,timeout_seconds,created_by) VALUES(?,?,?,1,?::jsonb,?::jsonb,?,?)",version,id,organizationId,write(d.inputSchema()),write(d.outputMapping()),d.timeoutSeconds(),user.id());
        Map<String,UUID> ids=new HashMap<>(); d.steps().forEach(s->ids.put(s.key(),UUID.randomUUID()));
        for(Step s:d.steps()) {
            String type=s.type().toUpperCase(Locale.ROOT); UUID stepId=ids.get(s.key());
            jdbc.update("INSERT INTO workflow_steps(id,workflow_version_id,step_key,step_type,agent_version_id,tool_id,config,retry_max_attempts,timeout_seconds,requires_approval) VALUES(?,?,?,?,?,?,?::jsonb,?,?,?)",stepId,version,s.key(),type,s.agentVersionId(),s.toolId(),write(s.config()),s.retryMaxAttempts(),s.timeoutSeconds(),s.requiresApproval());
            for(String dep:s.dependsOn()==null?List.<String>of():s.dependsOn()) jdbc.update("INSERT INTO workflow_step_dependencies(step_id,depends_on_step_id) VALUES(?,?)",stepId,ids.get(dep));
        }
        jdbc.update("UPDATE workflows SET current_version_id=? WHERE id=?",version,id);
        return new Workflow(id,d.name(),d.description(),d.triggerType().toUpperCase(Locale.ROOT),"DRAFT",1);
    }
    @PostMapping("/{workflowId}/publish") @Transactional public Workflow publish(@PathVariable UUID organizationId,@PathVariable UUID workflowId,@AuthenticationPrincipal CurrentUser user) {
        authorize(organizationId,user,"ORGANIZATION_ADMIN","WORKSPACE_MANAGER","DEVELOPER");
        var versions=jdbc.query("SELECT current_version_id FROM workflows WHERE id=? AND organization_id=? AND status<>'DEACTIVATED'",(rs,n)->rs.getObject(1,UUID.class),workflowId,organizationId);
        if(versions.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Workflow not found"); UUID version=versions.get(0);
        Integer count=jdbc.queryForObject("SELECT count(*) FROM workflow_steps WHERE workflow_version_id=?",Integer.class,version);
        if(count==null||count==0) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Workflow requires at least one step");
        jdbc.update("UPDATE workflow_versions SET published=true WHERE id=?",version); jdbc.update("UPDATE workflows SET status='PUBLISHED',updated_at=now() WHERE id=?",workflowId);
        return jdbc.queryForObject("SELECT id,name,description,trigger_type,status,1 FROM workflows WHERE id=?",(rs,n)->new Workflow(rs.getObject(1,UUID.class),rs.getString(2),rs.getString(3),rs.getString(4),rs.getString(5),rs.getInt(6)),workflowId);
    }
    @GetMapping public List<Workflow> list(@PathVariable UUID organizationId,@AuthenticationPrincipal CurrentUser user) {
        authorize(organizationId,user,"ORGANIZATION_ADMIN","WORKSPACE_MANAGER","DEVELOPER","OPERATOR","VIEWER");
        return jdbc.query("SELECT w.id,w.name,w.description,w.trigger_type,w.status,v.version_number FROM workflows w LEFT JOIN workflow_versions v ON v.id=w.current_version_id WHERE w.organization_id=? ORDER BY w.created_at DESC",(rs,n)->new Workflow(rs.getObject(1,UUID.class),rs.getString(2),rs.getString(3),rs.getString(4),rs.getString(5),(Integer)rs.getObject(6)),organizationId);
    }
    private void authorize(UUID org,CurrentUser user,String... roles) { OrganizationController.requireRole(jdbc,org,user.id(),roles); }
    private void validate(Definition d,UUID organizationId) {
        if (!Set.of("MANUAL","API","SCHEDULED","EVENT").contains(d.triggerType().toUpperCase(Locale.ROOT))) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid trigger type");
        Set<String> keys=new HashSet<>(); Set<String> allowed=Set.of("AGENT","TOOL","APPROVAL","CONDITION");
        for(Step s:d.steps()) { if(!keys.add(s.key())) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Duplicate step key: "+s.key()); String type=s.type().toUpperCase(Locale.ROOT); if(!allowed.contains(type)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Unsupported step type: "+s.type()); if(type.equals("AGENT")!=(s.agentVersionId()!=null) || type.equals("TOOL")!=(s.toolId()!=null)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Step reference does not match its type");
            if(s.agentVersionId()!=null && jdbc.queryForObject("SELECT count(*) FROM agent_versions av JOIN agents a ON a.id=av.agent_id WHERE av.id=? AND av.organization_id=? AND a.status='PUBLISHED'",Integer.class,s.agentVersionId(),organizationId)==0) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Agent version is unavailable in this organization");
            if(s.toolId()!=null && jdbc.queryForObject("SELECT count(*) FROM tools WHERE id=? AND enabled=true AND (organization_id=? OR organization_id IS NULL)",Integer.class,s.toolId(),organizationId)==0) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Tool is unavailable in this organization"); }
        for(Step s:d.steps()) for(String dep:s.dependsOn()==null?List.<String>of():s.dependsOn()) if(!keys.contains(dep)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Unknown dependency: "+dep);
        Set<String> done=new HashSet<>(); while(done.size()<keys.size()) { int before=done.size(); for(Step s:d.steps()) if((s.dependsOn()==null||done.containsAll(s.dependsOn()))&&done.add(s.key())) { } if(done.size()==before) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Workflow dependencies contain a cycle"); }
    }
    private String write(Object value) { try { return json.writeValueAsString(value==null?Map.of():value); } catch(JsonProcessingException e) { throw new IllegalArgumentException("Invalid JSON value",e); } }
}
