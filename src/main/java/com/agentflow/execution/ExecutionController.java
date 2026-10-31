package com.agentflow.execution;

import com.agentflow.identity.CurrentUser;
import com.agentflow.organization.OrganizationController;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.*;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController @RequestMapping("/api/v1")
public class ExecutionController {
    private final JdbcTemplate jdbc; private final ExecutionRunner runner; private final ObjectMapper json;
    public ExecutionController(JdbcTemplate jdbc,ExecutionRunner runner,ObjectMapper json){this.jdbc=jdbc;this.runner=runner;this.json=json;}
    public record StartRequest(@NotNull Map<String,Object> input) { }
    public record ExecutionView(UUID id,UUID workflowId,String status,Map<String,Object> input,Map<String,Object> output,String errorCode,String errorSummary,Instant createdAt,Instant startedAt,Instant finishedAt) { }
    public record Page<T>(List<T> items,int page,int size,long total) { }
    @PostMapping("/organizations/{organizationId}/workflows/{workflowId}/executions") @ResponseStatus(HttpStatus.ACCEPTED) @Transactional
    public ExecutionView start(@PathVariable UUID organizationId,@PathVariable UUID workflowId,@AuthenticationPrincipal CurrentUser user,@RequestHeader(value="Idempotency-Key",required=false) String key,@RequestHeader(value="X-Correlation-Id",required=false) String correlation,@Valid @RequestBody StartRequest request) {
        OrganizationController.requireRole(jdbc,organizationId,user.id(),"ORGANIZATION_ADMIN","WORKSPACE_MANAGER","DEVELOPER","OPERATOR");
        if(key!=null&&(key.isBlank()||key.length()>100)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Idempotency-Key must be 1 to 100 characters");
        var versions=jdbc.query("SELECT current_version_id FROM workflows WHERE id=? AND organization_id=? AND status='PUBLISHED'",(rs,n)->rs.getObject(1,UUID.class),workflowId,organizationId);
        if(versions.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Published workflow not found"); UUID version=versions.get(0);
        UUID id=UUID.randomUUID(); String input=write(request.input());
        if(input.length()>65536) throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE,"Execution input exceeds 64 KiB");
        if(key!=null){lockIdempotencyKey(organizationId,key);var prior=jdbc.query("SELECT id,workflow_id,status,input::text,output::text,error_code,error_summary,created_at,started_at,finished_at FROM workflow_executions WHERE organization_id=? AND idempotency_key=?",ExecutionController::mapExecution,organizationId,key);if(!prior.isEmpty()){ExecutionView existing=prior.get(0);if(!existing.workflowId().equals(workflowId)||!Objects.equals(existing.input(),request.input()))throw new ResponseStatusException(HttpStatus.CONFLICT,"Idempotency-Key was already used for a different request");return existing;}}
        jdbc.update("INSERT INTO workflow_executions(id,organization_id,workflow_id,workflow_version_id,status,input,idempotency_key,correlation_id,triggered_by,deadline_at) VALUES(?,?,?,?,'QUEUED',?::jsonb,?,?,?,now()+interval '24 hours')",id,organizationId,workflowId,version,input,key,correlation,user.id());
        jdbc.update("INSERT INTO execution_steps(execution_id,organization_id,step_id,status) SELECT ?,?,id,'PENDING' FROM workflow_steps WHERE workflow_version_id=?",id,organizationId,version);
        jdbc.update("INSERT INTO audit_events(organization_id,actor_user_id,action,resource_type,resource_id,outcome,correlation_id) VALUES(?,?,'EXECUTION_STARTED','EXECUTION',?,'ACCEPTED',?)",organizationId,user.id(),id,correlation);
        afterCommit(id); return jdbc.queryForObject("SELECT id,workflow_id,status,input::text,output::text,error_code,error_summary,created_at,started_at,finished_at FROM workflow_executions WHERE id=?",ExecutionController::mapExecution,id);
    }
    @GetMapping("/organizations/{organizationId}/executions") public Page<ExecutionView> list(@PathVariable UUID organizationId,@AuthenticationPrincipal CurrentUser user,@RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="20") int size,@RequestParam(required=false) String status) {
        OrganizationController.requireRole(jdbc,organizationId,user.id(),"ORGANIZATION_ADMIN","WORKSPACE_MANAGER","DEVELOPER","OPERATOR","VIEWER");
        if(page<0||size<1||size>100) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid pagination parameters");
        String filter=status==null?"":" AND status=?"; Object[] args=status==null?new Object[]{organizationId,size,page*size}:new Object[]{organizationId,status,size,page*size};
        List<ExecutionView> items=jdbc.query("SELECT id,workflow_id,status,input::text,output::text,error_code,error_summary,created_at,started_at,finished_at FROM workflow_executions WHERE organization_id=?"+filter+" ORDER BY created_at DESC LIMIT ? OFFSET ?",ExecutionController::mapExecution,args);
        Long total=status==null?jdbc.queryForObject("SELECT count(*) FROM workflow_executions WHERE organization_id=?",Long.class,organizationId):jdbc.queryForObject("SELECT count(*) FROM workflow_executions WHERE organization_id=? AND status=?",Long.class,organizationId,status);
        return new Page<>(items,page,size,total==null?0:total);
    }
    @GetMapping("/executions/{executionId}") public ExecutionView get(@PathVariable UUID executionId,@AuthenticationPrincipal CurrentUser user) {
        var rows=jdbc.query("SELECT e.organization_id,e.id,e.workflow_id,e.status,e.input::text,e.output::text,e.error_code,e.error_summary,e.created_at,e.started_at,e.finished_at FROM workflow_executions e WHERE e.id=?",(rs,n)->new Object[]{rs.getObject(1,UUID.class),map(rs,2)},executionId);
        if(rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Execution not found"); Object[] r=rows.get(0); OrganizationController.requireRole(jdbc,(UUID)r[0],user.id(),"ORGANIZATION_ADMIN","WORKSPACE_MANAGER","DEVELOPER","OPERATOR","VIEWER"); return (ExecutionView)r[1];
    }
    @GetMapping("/executions/{executionId}/steps") public List<Map<String,Object>> steps(@PathVariable UUID executionId,@AuthenticationPrincipal CurrentUser user) {
        UUID org=jdbc.query("SELECT organization_id FROM workflow_executions WHERE id=?",(rs,n)->rs.getObject(1,UUID.class),executionId).stream().findFirst().orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND,"Execution not found"));
        OrganizationController.requireRole(jdbc,org,user.id(),"ORGANIZATION_ADMIN","WORKSPACE_MANAGER","DEVELOPER","OPERATOR","VIEWER");
        return jdbc.query("SELECT s.step_key,es.status,es.attempt,es.summary,es.error_code,es.error_summary,es.prompt_tokens,es.completion_tokens,es.started_at,es.finished_at,es.output::text FROM execution_steps es JOIN workflow_steps s ON s.id=es.step_id WHERE es.execution_id=? ORDER BY s.step_key",(rs,n)->{Map<String,Object> m=new LinkedHashMap<>();m.put("stepKey",rs.getString(1));m.put("status",rs.getString(2));m.put("attempt",rs.getInt(3));m.put("summary",rs.getString(4));m.put("errorCode",rs.getString(5));m.put("errorSummary",rs.getString(6));m.put("promptTokens",rs.getInt(7));m.put("completionTokens",rs.getInt(8));m.put("startedAt",rs.getTimestamp(9)==null?null:rs.getTimestamp(9).toInstant());m.put("finishedAt",rs.getTimestamp(10)==null?null:rs.getTimestamp(10).toInstant());m.put("output",read(rs.getString(11)));return m;},executionId);
    }
    @PostMapping("/executions/{executionId}/cancel") @Transactional public ExecutionView cancel(@PathVariable UUID executionId,@AuthenticationPrincipal CurrentUser user) {
        UUID org=jdbc.query("SELECT organization_id FROM workflow_executions WHERE id=?",(rs,n)->rs.getObject(1,UUID.class),executionId).stream().findFirst().orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND,"Execution not found"));
        OrganizationController.requireRole(jdbc,org,user.id(),"ORGANIZATION_ADMIN","WORKSPACE_MANAGER","DEVELOPER","OPERATOR");
        int changed=jdbc.update("UPDATE workflow_executions SET status='CANCELLED',finished_at=now(),updated_at=now() WHERE id=? AND status IN ('PENDING','QUEUED','RUNNING','WAITING_FOR_APPROVAL')",executionId);
        if(changed==0) throw new ResponseStatusException(HttpStatus.CONFLICT,"Execution cannot be cancelled in its current state");
        jdbc.update("UPDATE execution_steps SET status='CANCELLED',finished_at=now(),updated_at=now() WHERE execution_id=? AND status IN ('PENDING','QUEUED','RUNNING')",executionId);
        jdbc.update("UPDATE execution_steps SET status='CANCELLED',finished_at=now(),updated_at=now() WHERE execution_id=? AND status='WAITING_FOR_APPROVAL'",executionId);
        jdbc.update("UPDATE approval_requests SET status='EXPIRED',updated_at=now() WHERE execution_id=? AND status IN ('PENDING','ESCALATED')",executionId);
        return jdbc.queryForObject("SELECT id,workflow_id,status,input::text,output::text,error_code,error_summary,created_at,started_at,finished_at FROM workflow_executions WHERE id=?",ExecutionController::mapExecution,executionId);
    }
    private void afterCommit(UUID id){TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization(){@Override public void afterCommit(){runner.enqueue(id);}});}
    private void lockIdempotencyKey(UUID organizationId,String key){jdbc.execute((org.springframework.jdbc.core.ConnectionCallback<Void>)connection->{try(var statement=connection.prepareStatement("SELECT pg_advisory_xact_lock(hashtextextended(?,0))")){statement.setString(1,organizationId+":"+key);statement.execute();}return null;});}
    private String write(Object value){try{return json.writeValueAsString(value);}catch(Exception e){throw new IllegalArgumentException("Input must be JSON serializable",e);}}
    private Map<String,Object> read(String text){try{return text==null?null:json.readValue(text,new com.fasterxml.jackson.core.type.TypeReference<>(){});}catch(Exception e){return Map.of();}}
    private static ExecutionView mapExecution(java.sql.ResultSet rs,int rowNum)throws java.sql.SQLException{return map(rs,1);}
    private static ExecutionView map(java.sql.ResultSet rs,int start)throws java.sql.SQLException{return new ExecutionView(rs.getObject(start,UUID.class),rs.getObject(start+1,UUID.class),rs.getString(start+2),parse(rs.getString(start+3)),parse(rs.getString(start+4)),rs.getString(start+5),rs.getString(start+6),rs.getTimestamp(start+7).toInstant(),rs.getTimestamp(start+8)==null?null:rs.getTimestamp(start+8).toInstant(),rs.getTimestamp(start+9)==null?null:rs.getTimestamp(start+9).toInstant());}
    private static Map<String,Object> parse(String s){if(s==null)return null;try{return new ObjectMapper().readValue(s,new com.fasterxml.jackson.core.type.TypeReference<>(){});}catch(Exception e){return Map.of();}}
}
