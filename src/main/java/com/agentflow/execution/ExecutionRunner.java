package com.agentflow.execution;

import com.agentflow.ai.AgentAiService;
import com.agentflow.identity.CurrentUser;
import com.agentflow.tool.ToolExecutionService;
import com.agentflow.shared.config.AgentFlowProperties;
import com.agentflow.shared.security.Redactor;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.Executor;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/** Persistent local worker: Postgres is authoritative; queued jobs are recovered after restart. */
@Service
public class ExecutionRunner {
    private static final Logger log=LoggerFactory.getLogger(ExecutionRunner.class);
    private final Set<UUID> activeExecutions=ConcurrentHashMap.newKeySet();
    private final JdbcTemplate jdbc; private final AgentAiService ai; private final ToolExecutionService tools; private final ObjectMapper json; private final Executor executor; private final AgentFlowProperties properties;
    public ExecutionRunner(JdbcTemplate jdbc,AgentAiService ai,ToolExecutionService tools,ObjectMapper json,@Qualifier("executionExecutor") Executor executor,AgentFlowProperties properties){this.jdbc=jdbc;this.ai=ai;this.tools=tools;this.json=json;this.executor=executor;this.properties=properties;}
    public void enqueue(UUID executionId){executor.execute(()->run(executionId));}
    @Scheduled(fixedDelayString="${agentflow.execution.recovery-sweep-interval:60s}")
    public void recoverQueued(){
        jdbc.update("UPDATE approval_requests SET status='EXPIRED',updated_at=now() WHERE status IN ('PENDING','ESCALATED') AND deadline_at<=now()");
        jdbc.update("UPDATE execution_steps es SET status='TIMED_OUT',error_code='DEADLINE_EXCEEDED',error_summary='Execution deadline elapsed',finished_at=now(),updated_at=now() FROM workflow_executions e WHERE e.id=es.execution_id AND e.status IN ('QUEUED','RUNNING','WAITING_FOR_APPROVAL') AND e.deadline_at<=now() AND es.status IN ('PENDING','QUEUED','RUNNING','WAITING_FOR_APPROVAL')");
        jdbc.update("UPDATE workflow_executions SET status='TIMED_OUT',error_code='DEADLINE_EXCEEDED',error_summary='Execution deadline elapsed',finished_at=now(),updated_at=now() WHERE status IN ('QUEUED','RUNNING','WAITING_FOR_APPROVAL') AND deadline_at<=now()");
        jdbc.update("UPDATE execution_steps es SET status='FAILED',error_code='APPROVAL_EXPIRED',error_summary='Approval deadline elapsed',finished_at=now(),updated_at=now() FROM approval_requests ar,workflow_executions e WHERE ar.execution_step_id=es.id AND e.id=ar.execution_id AND ar.status='EXPIRED' AND ar.deadline_at<=now() AND es.status='WAITING_FOR_APPROVAL' AND e.status='WAITING_FOR_APPROVAL'");
        jdbc.update("UPDATE workflow_executions e SET status='FAILED',error_code='APPROVAL_EXPIRED',error_summary='Approval deadline elapsed',finished_at=now(),updated_at=now() WHERE e.status='WAITING_FOR_APPROVAL' AND EXISTS (SELECT 1 FROM approval_requests ar WHERE ar.execution_id=e.id AND ar.status='EXPIRED' AND ar.deadline_at<=now())");
        List<UUID> stale=jdbc.query("SELECT id FROM workflow_executions WHERE status='RUNNING' AND updated_at < now()-interval '10 minutes'",(rs,n)->rs.getObject(1,UUID.class));
        for(UUID id:stale) if(!activeExecutions.contains(id)) jdbc.update("UPDATE workflow_executions SET status='FAILED',error_code='WORKER_INTERRUPTED',error_summary='Worker stopped during execution; inspect side effects before retrying',finished_at=now(),updated_at=now() WHERE id=? AND status='RUNNING' AND updated_at < now()-interval '10 minutes'",id);
        List<UUID> ids=jdbc.query("SELECT id FROM workflow_executions WHERE status='QUEUED' ORDER BY created_at LIMIT 100",(rs,n)->rs.getObject(1,UUID.class)); ids.forEach(this::enqueue);
    }
    public void run(UUID id){ if(activeExecutions.add(id)) try { runClaimed(id); } finally { activeExecutions.remove(id); } }
    private void runClaimed(UUID id){
        try {
            int started=jdbc.update("UPDATE workflow_executions SET status='RUNNING',started_at=coalesce(started_at,now()),updated_at=now() WHERE id=? AND status='QUEUED'",id); if(started==0)return;
            var rows=jdbc.query("SELECT organization_id,workflow_id,workflow_version_id,input::text,triggered_by FROM workflow_executions WHERE id=?",(rs,n)->new Object[]{rs.getObject(1,UUID.class),rs.getObject(2,UUID.class),rs.getObject(3,UUID.class),rs.getString(4),rs.getObject(5,UUID.class)},id); if(rows.isEmpty())return;
            Object[] exec=rows.get(0); UUID org=(UUID)exec[0], version=(UUID)exec[2]; Map<String,Object> workflowInput=readMap((String)exec[3]); Map<String,Object> outputs=new LinkedHashMap<>();
            List<Step> steps=topological(loadSteps(version,id)); Map<String,Step> byId=new HashMap<>(); steps.forEach(s->byId.put(s.key,s));
            for(Step step:steps){
                if(!Boolean.TRUE.equals(jdbc.queryForObject("SELECT status='RUNNING' FROM workflow_executions WHERE id=?",Boolean.class,id))) return;
                if("COMPLETED".equals(step.status)||"SKIPPED".equals(step.status)) { if(step.output!=null)outputs.put(step.key,readAny(step.output)); continue; }
                if(step.dependencies.stream().anyMatch(k->byId.get(k)==null||"FAILED".equals(byId.get(k).status))) throw new IllegalStateException("A dependency failed or is invalid");
                if(step.dependencies.stream().anyMatch(k->"SKIPPED".equals(byId.get(k).status))){finishStep(id,step,"SKIPPED",null,"Skipped because a dependency branch was not selected",0,0);step.status="SKIPPED";continue;}
                if(!step.dependencies.stream().allMatch(k->outputs.containsKey(k)||"SKIPPED".equals(byId.get(k).status))) throw new IllegalStateException("Workflow dependency order is invalid");
                if(!condition(step,workflowInput,outputs)){finishStep(id,step,"SKIPPED",null,"Condition evaluated false",0,0);step.status="SKIPPED";continue;}
                step.approved=approvalSatisfied(id,step);
                if(needsApproval(org,id,step)) { createApproval(id,org,step); return; }
                Map<String,Object> input=new LinkedHashMap<>(); input.put("workflowInput",workflowInput); input.put("priorSteps",outputs); String inputJson=write(input);
                int maxAttempts=retryMayRepeatSideEffect(org,step)?1:Math.max(1,Math.min(step.retry,10)); Exception last=null; boolean succeeded=false;
                for(int attempt=1;attempt<=maxAttempts;attempt++){
                    int claimedStep=jdbc.update("UPDATE execution_steps SET status='RUNNING',attempt=?,input=?::jsonb,started_at=coalesce(started_at,now()),error_code=NULL,error_summary=NULL,updated_at=now() WHERE execution_id=? AND step_id=? AND status IN ('PENDING','RUNNING') AND EXISTS (SELECT 1 FROM workflow_executions WHERE id=? AND status='RUNNING')",attempt,inputJson,id,step.id,id);
                    if(claimedStep==0)return;
                    try {
                        Object value; int promptTokens=0,completionTokens=0;
                        if(step.type.equals("TOOL")) {
                            ToolExecutionService.Result result=tools.execute(org,step.toolId,step.config,step.approved); value=result.output();
                            jdbc.update("INSERT INTO tool_invocations(execution_step_id,organization_id,tool_id,status,input_redacted,output_redacted,duration_ms) SELECT id,?,?, 'SUCCEEDED',?::jsonb,?::jsonb,0 FROM execution_steps WHERE execution_id=? AND step_id=?",org,result.toolId(),write(Redactor.sanitize(step.config)),write(Redactor.sanitize(value)),id,step.id);
                        } else if(step.type.equals("AGENT")) {
                            String task=Objects.toString(step.config.get("task"),json.writeValueAsString(input)); var response=ai.invoke(org,step.agentVersionId,task); value=Map.of("text",response.text(),"model",response.model()); promptTokens=response.promptTokens(); completionTokens=response.completionTokens();
                            recordUsage(org,id,step.id,step.agentVersionId,response.model(),promptTokens,completionTokens);
                        } else if(step.type.equals("CONDITION")) { value=Map.of("condition",true); }
                        else { value=Map.of("approved",true); }
                        String output=write(value); int stepCompleted=jdbc.update("UPDATE execution_steps SET status='COMPLETED',output=?::jsonb,summary=?,prompt_tokens=?,completion_tokens=?,finished_at=now(),updated_at=now() WHERE execution_id=? AND step_id=? AND status='RUNNING'",output,summary(value),promptTokens,completionTokens,id,step.id);
                        if(stepCompleted==0)return;
                        step.status="COMPLETED";step.output=output; outputs.put(step.key,value); succeeded=true; break;
                    } catch(Exception ex){last=ex; jdbc.update("UPDATE execution_steps SET error_code='STEP_FAILED',error_summary=?,updated_at=now() WHERE execution_id=? AND step_id=? AND status='RUNNING'",safe(ex),id,step.id); if(step.type.equals("TOOL")&&step.toolId!=null)jdbc.update("INSERT INTO tool_invocations(execution_step_id,organization_id,tool_id,status,input_redacted,error_summary,duration_ms) SELECT id,?,?,'FAILED',?::jsonb,?,0 FROM execution_steps WHERE execution_id=? AND step_id=?",org,step.toolId,write(Redactor.sanitize(step.config)),safe(ex),id,step.id); if(attempt<maxAttempts) try{Thread.sleep(Math.min(2000L*attempt,10000L));}catch(InterruptedException ie){Thread.currentThread().interrupt();throw new IllegalStateException("Execution interrupted",ie);} }
                }
                if(!succeeded){jdbc.update("UPDATE execution_steps SET status='FAILED',finished_at=now(),updated_at=now() WHERE execution_id=? AND step_id=? AND status='RUNNING'",id,step.id);throw last==null?new IllegalStateException("Step failed"):last;}
            }
            int completed=jdbc.update("UPDATE workflow_executions SET status='COMPLETED',output=?::jsonb,finished_at=now(),updated_at=now() WHERE id=? AND status='RUNNING'",write(outputs),id);
            if(completed>0) audit(org,(UUID)exec[4],"EXECUTION_COMPLETED",id,"SUCCESS");
        } catch(Exception e){ log.warn("Execution {} failed: {}",id,safe(e)); jdbc.update("UPDATE workflow_executions SET status='FAILED',error_code='EXECUTION_FAILED',error_summary=?,finished_at=now(),updated_at=now() WHERE id=? AND status IN ('RUNNING','QUEUED')",safe(e),id); }
    }
    private List<Step> loadSteps(UUID version,UUID execution){return jdbc.query("SELECT s.id,s.step_key,s.step_type,s.agent_version_id,s.tool_id,s.config::text,s.retry_max_attempts,s.requires_approval,coalesce(es.status,'PENDING'),es.output::text,array_remove(array_agg(dep.step_key),NULL) FROM workflow_steps s LEFT JOIN workflow_step_dependencies d ON d.step_id=s.id LEFT JOIN workflow_steps dep ON dep.id=d.depends_on_step_id LEFT JOIN execution_steps es ON es.step_id=s.id AND es.execution_id=? WHERE s.workflow_version_id=? GROUP BY s.id,es.status,es.output ORDER BY s.step_key",(rs,n)->new Step(rs.getObject(1,UUID.class),rs.getString(2),rs.getString(3),rs.getObject(4,UUID.class),rs.getObject(5,UUID.class),readMap(rs.getString(6)),rs.getInt(7),rs.getBoolean(8),rs.getString(9),rs.getString(10),stringArray(rs.getArray(11))),execution,version);}
    private List<Step> topological(List<Step> steps){List<Step> ordered=new ArrayList<>();Set<String> done=new HashSet<>();while(ordered.size()<steps.size()){int before=ordered.size();for(Step s:steps)if(!done.contains(s.key)&&done.containsAll(s.dependencies)){ordered.add(s);done.add(s.key);}if(ordered.size()==before)throw new IllegalStateException("Workflow dependency graph is cyclic or invalid");}return ordered;}
    private boolean needsApproval(UUID org,UUID execution,Step s){if(approvalSatisfied(execution,s))return false;if(s.requiresApproval||s.type.equals("APPROVAL"))return true;if(s.toolId!=null)return Boolean.TRUE.equals(jdbc.queryForObject("SELECT requires_approval FROM tools WHERE id=?",Boolean.class,s.toolId));if(s.agentVersionId!=null)return jdbc.queryForObject("SELECT count(*)>0 FROM agent_version_tools avt JOIN tools t ON t.id=avt.tool_id WHERE avt.agent_version_id=? AND t.requires_approval",Boolean.class,s.agentVersionId);return false;}
    private boolean retryMayRepeatSideEffect(UUID org,Step step){if(step.toolId!=null)return Boolean.TRUE.equals(jdbc.queryForObject("SELECT requires_approval OR name='http_api' FROM tools WHERE id=? AND (organization_id=? OR organization_id IS NULL)",Boolean.class,step.toolId,org));if(step.agentVersionId!=null)return Boolean.TRUE.equals(jdbc.queryForObject("SELECT count(*)>0 FROM agent_version_tools avt JOIN tools t ON t.id=avt.tool_id WHERE avt.agent_version_id=? AND (t.requires_approval OR t.name='http_api')",Boolean.class,step.agentVersionId));return false;}
    private boolean approvalSatisfied(UUID execution,Step step){return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM approval_requests ar JOIN execution_steps es ON es.id=ar.execution_step_id WHERE ar.execution_id=? AND es.step_id=? AND ar.status='APPROVED')",Boolean.class,execution,step.id));}
    private void createApproval(UUID execution,UUID org,Step step){
        int stepWaiting=jdbc.update("UPDATE execution_steps SET status='WAITING_FOR_APPROVAL',updated_at=now() WHERE execution_id=? AND step_id=? AND status IN ('PENDING','RUNNING') AND EXISTS (SELECT 1 FROM workflow_executions WHERE id=? AND status='RUNNING')",execution,step.id,execution);
        if(stepWaiting==0) return;
        int executionWaiting=jdbc.update("UPDATE workflow_executions SET status='WAITING_FOR_APPROVAL',updated_at=now() WHERE id=? AND status='RUNNING' AND EXISTS (SELECT 1 FROM execution_steps WHERE execution_id=? AND step_id=? AND status='WAITING_FOR_APPROVAL')",execution,execution,step.id);
        if(executionWaiting==0) throw new IllegalStateException("Could not transition execution to approval wait state");
        UUID executionStepId=jdbc.queryForObject("SELECT id FROM execution_steps WHERE execution_id=? AND step_id=?",UUID.class,execution,step.id);
        int inserted=jdbc.update("INSERT INTO approval_requests(organization_id,execution_id,execution_step_id,status,required_role,payload_summary,deadline_at) VALUES(?,?,?,'PENDING','ORGANIZATION_ADMIN',?,LEAST(now()+(? * interval '1 second'),(SELECT deadline_at FROM workflow_executions WHERE id=?))) ON CONFLICT (execution_step_id) WHERE status IN ('PENDING','ESCALATED') DO NOTHING",org,execution,executionStepId,"Approval required before step '"+step.key+"'",properties.execution().approvalDefaultDeadline().toSeconds(),execution);
        if(inserted==0 && !Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM approval_requests WHERE execution_step_id=? AND status IN ('PENDING','ESCALATED'))",Boolean.class,executionStepId))) throw new IllegalStateException("Could not persist required approval request");
    }
    private boolean condition(Step step,Map<String,Object> input,Map<String,Object> outputs){if(!step.type.equals("CONDITION"))return true;String path=Objects.toString(step.config.get("path"),"");Object expected=step.config.get("equals");Object actual=resolve(path,input,outputs);return Objects.equals(Objects.toString(actual,null),Objects.toString(expected,null));}
    private Object resolve(String path,Map<String,Object> input,Map<String,Object> outputs){String[] p=path.split("\\.",3);if(p.length<2)return null;Object value=switch(p[0]){case "input"->input.get(p[1]);case "steps"->outputs.get(p[1]);default->null;};if(p.length==3&&value instanceof Map<?,?> map)return map.get(p[2]);return value;}
    private void finishStep(UUID execution,Step step,String status,Object output,String summary,int p,int c){jdbc.update("UPDATE execution_steps SET status=?,output=?::jsonb,summary=?,finished_at=now(),updated_at=now() WHERE execution_id=? AND step_id=? AND status='PENDING' AND EXISTS (SELECT 1 FROM workflow_executions WHERE id=? AND status='RUNNING')",status,output==null?"null":write(output),summary,execution,step.id,execution);}
    private void recordUsage(UUID org,UUID execution,UUID step,UUID agentVersion,String model,int prompt,int completion){var ai=properties.ai();java.math.BigDecimal cost=ai.inputUsdPer1kTokens().multiply(java.math.BigDecimal.valueOf(prompt)).add(ai.outputUsdPer1kTokens().multiply(java.math.BigDecimal.valueOf(completion))).divide(java.math.BigDecimal.valueOf(1000),6,java.math.RoundingMode.HALF_UP);jdbc.update("INSERT INTO usage_records(organization_id,execution_id,execution_step_id,agent_version_id,provider,model,prompt_tokens,completion_tokens,estimated_cost_usd) VALUES(?,?,?,?,'openai',?,?,?,?)",org,execution,step,agentVersion,model,prompt,completion,cost);}
    private void audit(UUID org,UUID actor,String action,UUID id,String outcome){jdbc.update("INSERT INTO audit_events(organization_id,actor_user_id,action,resource_type,resource_id,outcome) VALUES(?,?,?,'EXECUTION',?,?)",org,actor,action,id,outcome);}
    private static String summary(Object o){String s=Redactor.sanitizeText(String.valueOf(Redactor.sanitize(o)));return s.length()>500?s.substring(0,500):s;}
    private static String safe(Exception e){String m=e.getMessage();if(m==null)m=e.getClass().getSimpleName();m=Redactor.sanitizeText(m);return m.length()>500?m.substring(0,500):m;}
    private Map<String,Object> readMap(String s){try{return s==null?new LinkedHashMap<>():json.readValue(s,new TypeReference<>(){});}catch(Exception e){throw new IllegalStateException("Stored JSON is invalid",e);}}
    private String write(Object o){try{return json.writeValueAsString(o);}catch(Exception e){throw new IllegalStateException("Could not encode execution data",e);}}
    private List<String> stringArray(java.sql.Array a){if(a==null)return List.of();try{Object[] v=(Object[])a.getArray();return Arrays.stream(v).map(Object::toString).toList();}catch(Exception e){throw new IllegalStateException(e);}}
    private Object readAny(String s){try{return json.readValue(s,Object.class);}catch(Exception e){throw new IllegalStateException("Stored JSON is invalid",e);}}
    private static final class Step {final UUID id;final String key,type;final UUID agentVersionId,toolId;final Map<String,Object> config;final int retry;final boolean requiresApproval;String status,output;final List<String> dependencies;boolean approved;
        Step(UUID i,String k,String t,UUID a,UUID tool,Map<String,Object> c,int r,boolean ap,String st,String o,List<String>d){id=i;key=k;type=t;agentVersionId=a;toolId=tool;config=c;retry=r;requiresApproval=ap;status=st;output=o;dependencies=d;}}
}
