package com.agentflow.approval;

import com.agentflow.execution.ExecutionRunner;
import com.agentflow.identity.CurrentUser;
import com.agentflow.organization.OrganizationController;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController @RequestMapping("/api/v1")
public class ApprovalController {
    private final JdbcTemplate jdbc; private final ExecutionRunner runner;
    public ApprovalController(JdbcTemplate jdbc,ExecutionRunner runner){this.jdbc=jdbc;this.runner=runner;}
    public record Approval(UUID id,UUID executionId,String status,String requiredRole,String payloadSummary,Instant deadlineAt,Instant createdAt) { }
    public record Decision(@Size(max=2000) String comment) { }
    @GetMapping("/organizations/{organizationId}/approvals") public List<Approval> queue(@PathVariable UUID organizationId,@AuthenticationPrincipal CurrentUser user,@RequestParam(defaultValue="PENDING") String status) {
        OrganizationController.requireRole(jdbc,organizationId,user.id(),"ORGANIZATION_ADMIN","WORKSPACE_MANAGER");
        if(!Set.of("PENDING","ESCALATED","APPROVED","REJECTED","EXPIRED").contains(status)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid approval status");
        return jdbc.query("SELECT id,execution_id,status,required_role,payload_summary,deadline_at,created_at FROM approval_requests WHERE organization_id=? AND status=? ORDER BY deadline_at",(rs,n)->new Approval(rs.getObject(1,UUID.class),rs.getObject(2,UUID.class),rs.getString(3),rs.getString(4),rs.getString(5),rs.getTimestamp(6).toInstant(),rs.getTimestamp(7).toInstant()),organizationId,status);
    }
    @PostMapping("/approvals/{approvalId}/approve") @Transactional(noRollbackFor=ResponseStatusException.class) public Approval approve(@PathVariable UUID approvalId,@AuthenticationPrincipal CurrentUser user,@Valid @RequestBody(required=false) Decision decision) {
        return decide(approvalId,user,decision==null?null:decision.comment(),true);
    }
    @PostMapping("/approvals/{approvalId}/reject") @Transactional(noRollbackFor=ResponseStatusException.class) public Approval reject(@PathVariable UUID approvalId,@AuthenticationPrincipal CurrentUser user,@Valid @RequestBody(required=false) Decision decision) {
        return decide(approvalId,user,decision==null?null:decision.comment(),false);
    }
    @GetMapping("/executions/{executionId}/approvals") public List<Approval> history(@PathVariable UUID executionId,@AuthenticationPrincipal CurrentUser user) {
        UUID org=jdbc.query("SELECT organization_id FROM workflow_executions WHERE id=?",(rs,n)->rs.getObject(1,UUID.class),executionId).stream().findFirst().orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND,"Execution not found"));
        OrganizationController.requireRole(jdbc,org,user.id(),"ORGANIZATION_ADMIN","WORKSPACE_MANAGER","DEVELOPER","OPERATOR","VIEWER");
        return jdbc.query("SELECT id,execution_id,status,required_role,payload_summary,deadline_at,created_at FROM approval_requests WHERE execution_id=? ORDER BY created_at",(rs,n)->new Approval(rs.getObject(1,UUID.class),rs.getObject(2,UUID.class),rs.getString(3),rs.getString(4),rs.getString(5),rs.getTimestamp(6).toInstant(),rs.getTimestamp(7).toInstant()),executionId);
    }
    private Approval decide(UUID approvalId,CurrentUser user,String comment,boolean approve) {
        var rows=jdbc.query("SELECT organization_id,execution_id,execution_step_id,required_role,status,deadline_at FROM approval_requests WHERE id=? FOR UPDATE",(rs,n)->new Object[]{rs.getObject(1,UUID.class),rs.getObject(2,UUID.class),rs.getObject(3,UUID.class),rs.getString(4),rs.getString(5),rs.getTimestamp(6).toInstant()},approvalId);
        if(rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Approval not found"); Object[] a=rows.get(0); UUID org=(UUID)a[0],execution=(UUID)a[1],step=(UUID)a[2];
        OrganizationController.requireRole(jdbc,org,user.id(),"ORGANIZATION_ADMIN");
        if(!"PENDING".equals(a[4])) throw new ResponseStatusException(HttpStatus.CONFLICT,"Approval has already been decided");
        if(((Instant)a[5]).isBefore(Instant.now())) { jdbc.update("UPDATE approval_requests SET status='EXPIRED',updated_at=now() WHERE id=?",approvalId); throw new ResponseStatusException(HttpStatus.GONE,"Approval deadline has passed"); }
        String status=approve?"APPROVED":"REJECTED";
        int updated=jdbc.update("UPDATE approval_requests SET status=?,decided_by=?,decided_at=now(),reviewer_comment=?,updated_at=now(),version=version+1 WHERE id=? AND status='PENDING' AND deadline_at>now()",status,user.id(),comment,approvalId);
        if(updated==0) throw new ResponseStatusException(HttpStatus.CONFLICT,"Approval was already decided");
        if(approve) { jdbc.update("UPDATE execution_steps SET status='PENDING',updated_at=now() WHERE id=? AND status='WAITING_FOR_APPROVAL'",step); jdbc.update("UPDATE workflow_executions SET status='QUEUED',updated_at=now() WHERE id=? AND status='WAITING_FOR_APPROVAL'",execution); TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization(){@Override public void afterCommit(){runner.enqueue(execution);}}); }
        else { jdbc.update("UPDATE execution_steps SET status='FAILED',error_code='APPROVAL_REJECTED',error_summary=?,finished_at=now(),updated_at=now() WHERE id=?",comment==null?"Approval rejected":comment,step); jdbc.update("UPDATE workflow_executions SET status='FAILED',error_code='APPROVAL_REJECTED',error_summary=?,finished_at=now(),updated_at=now() WHERE id=?",comment==null?"Approval rejected":comment,execution); }
        jdbc.update("INSERT INTO audit_events(organization_id,actor_user_id,action,resource_type,resource_id,outcome) VALUES(?,?,?,'APPROVAL',?,?)",org,user.id(),approve?"APPROVAL_APPROVED":"APPROVAL_REJECTED",approvalId,status);
        return jdbc.queryForObject("SELECT id,execution_id,status,required_role,payload_summary,deadline_at,created_at FROM approval_requests WHERE id=?",(rs,n)->new Approval(rs.getObject(1,UUID.class),rs.getObject(2,UUID.class),rs.getString(3),rs.getString(4),rs.getString(5),rs.getTimestamp(6).toInstant(),rs.getTimestamp(7).toInstant()),approvalId);
    }
}
