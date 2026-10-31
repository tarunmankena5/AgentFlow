package com.agentflow.monitoring;

import com.agentflow.identity.CurrentUser;
import com.agentflow.organization.OrganizationController;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

@RestController @RequestMapping("/api/v1/organizations/{organizationId}")
public class MonitoringController {
    private final JdbcTemplate jdbc;
    public MonitoringController(JdbcTemplate jdbc){this.jdbc=jdbc;}
    public record Analytics(long total,long completed,long failed,long waitingForApproval,long promptTokens,long completionTokens,BigDecimal estimatedCostUsd) { }
    public record Audit(UUID id,UUID actorUserId,String action,String resourceType,UUID resourceId,String outcome,String correlationId,Instant createdAt) { }
    @GetMapping("/analytics") public Analytics analytics(@PathVariable UUID organizationId,@AuthenticationPrincipal CurrentUser user,@RequestParam(defaultValue="30") int days) {
        OrganizationController.requireRole(jdbc,organizationId,user.id(),"ORGANIZATION_ADMIN","WORKSPACE_MANAGER","VIEWER");
        if(days<1||days>365) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"days must be between 1 and 365");
        return jdbc.queryForObject("SELECT count(DISTINCT e.id),count(DISTINCT e.id) FILTER(WHERE e.status='COMPLETED'),count(DISTINCT e.id) FILTER(WHERE e.status='FAILED'),count(DISTINCT e.id) FILTER(WHERE e.status='WAITING_FOR_APPROVAL'),coalesce((SELECT sum(u.prompt_tokens) FROM usage_records u JOIN workflow_executions x ON x.id=u.execution_id WHERE u.organization_id=? AND x.created_at>=now()-(? * interval '1 day')),0),coalesce((SELECT sum(u.completion_tokens) FROM usage_records u JOIN workflow_executions x ON x.id=u.execution_id WHERE u.organization_id=? AND x.created_at>=now()-(? * interval '1 day')),0),coalesce((SELECT sum(u.estimated_cost_usd) FROM usage_records u JOIN workflow_executions x ON x.id=u.execution_id WHERE u.organization_id=? AND x.created_at>=now()-(? * interval '1 day')),0) FROM workflow_executions e WHERE e.organization_id=? AND e.created_at>=now()-(? * interval '1 day')",(rs,n)->new Analytics(rs.getLong(1),rs.getLong(2),rs.getLong(3),rs.getLong(4),rs.getLong(5),rs.getLong(6),rs.getBigDecimal(7)),organizationId,days,organizationId,days,organizationId,days,organizationId,days);
    }
    @GetMapping("/audit-logs") public List<Audit> audit(@PathVariable UUID organizationId,@AuthenticationPrincipal CurrentUser user,@RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="50") int size) {
        OrganizationController.requireRole(jdbc,organizationId,user.id(),"ORGANIZATION_ADMIN","WORKSPACE_MANAGER","VIEWER");
        if(page<0||size<1||size>200) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid pagination parameters");
        return jdbc.query("SELECT id,actor_user_id,action,resource_type,resource_id,outcome,correlation_id,created_at FROM audit_events WHERE organization_id=? ORDER BY created_at DESC LIMIT ? OFFSET ?",(rs,n)->new Audit(rs.getObject(1,UUID.class),rs.getObject(2,UUID.class),rs.getString(3),rs.getString(4),rs.getObject(5,UUID.class),rs.getString(6),rs.getString(7),rs.getTimestamp(8).toInstant()),organizationId,size,page*size);
    }
}
