package com.agentflow.ai;

import com.agentflow.shared.config.AgentFlowProperties;
import com.agentflow.tool.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import org.springframework.ai.openai.OpenAiChatOptions;

/** Provider-neutral application service; Spring AI OpenAI is the initial ChatModel adapter. */
@Service
public class AgentAiService {
    private final JdbcTemplate jdbc; private final ObjectProvider<ChatModel> chatModels; private final ToolCallingManager toolCallingManager; private final Executor providerExecutor; private final CalculatorTool calculator; private final DateTimeTool dateTime; private final StructuredExtractTool extract; private final AgentFlowProperties properties; private final ObjectMapper json;
    public AgentAiService(JdbcTemplate jdbc,ObjectProvider<ChatModel> chatModels,ToolCallingManager toolCallingManager,@Qualifier("aiProviderExecutor") Executor providerExecutor,CalculatorTool calculator,DateTimeTool dateTime,StructuredExtractTool extract,AgentFlowProperties properties,ObjectMapper json) { this.jdbc=jdbc;this.chatModels=chatModels;this.toolCallingManager=toolCallingManager;this.providerExecutor=providerExecutor;this.calculator=calculator;this.dateTime=dateTime;this.extract=extract;this.properties=properties;this.json=json; }
    public record Result(String text,String model,int promptTokens,int completionTokens) { }
    public Result invoke(UUID organizationId,UUID agentVersionId,String task) {
        if(task==null||task.isBlank()||task.length()>20000) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Task must contain 1 to 20000 characters");
        var rows=jdbc.query("SELECT av.system_prompt,av.llm_provider,av.model,av.temperature,av.max_steps,av.max_token_budget,av.timeout_seconds,a.status FROM agent_versions av JOIN agents a ON a.id=av.agent_id WHERE av.id=? AND av.organization_id=?",(rs,n)->new Object[]{rs.getString(1),rs.getString(2),rs.getString(3),rs.getBigDecimal(4),rs.getInt(5),rs.getInt(6),rs.getInt(7),rs.getString(8)},agentVersionId,organizationId);
        if(rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Agent version not found"); Object[] row=rows.get(0);
        if(!"PUBLISHED".equals(row[7])) throw new ResponseStatusException(HttpStatus.CONFLICT,"Agent must be published before execution");
        if(!"openai".equalsIgnoreCase((String)row[1])) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"This deployment currently supports the OpenAI provider only");
        ChatModel model=chatModels.getIfAvailable(); if(model==null) throw new IllegalStateException("AI provider is disabled. Configure AI_CHAT_MODEL=openai and OPENAI_API_KEY.");
        List<String> assigned=jdbc.query("SELECT t.name FROM agent_version_tools avt JOIN tools t ON t.id=avt.tool_id WHERE avt.agent_version_id=? AND t.enabled=true AND (t.organization_id=? OR t.organization_id IS NULL)",(rs,n)->rs.getString(1),agentVersionId,organizationId);
        List<Object> tools=new ArrayList<>();
        for(String name:assigned) switch(name) {
            case "calculator" -> tools.add(calculator);
            case "date_time" -> tools.add(dateTime);
            case "structured_extract" -> tools.add(extract);
            case "document_search" -> tools.add(new DocumentSearchTool(jdbc,organizationId));
            case "http_api" -> tools.add(new HttpApiTool(properties,json));
            default -> throw new IllegalStateException("Assigned tool has no registered executor: "+name);
        }
        OpenAiChatOptions options=OpenAiChatOptions.builder().model((String)row[2]).temperature(((BigDecimal)row[3]).doubleValue())
                .maxTokens((Integer)row[5]).parallelToolCalls(false).internalToolExecutionEnabled(false)
                .toolCallbacks(ToolCallbacks.from(tools.toArray())).build();
        Prompt prompt=new Prompt(List.<Message>of(new SystemMessage((String)row[0]),new UserMessage(task)),options);
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos((Integer)row[6]);
        ChatResponse response=callModel(model,prompt,deadline); int totalPromptTokens=0,totalCompletionTokens=0,totalToolCalls=0;
        while(true){
            Integer promptTokens=response.getMetadata().getUsage()==null?null:response.getMetadata().getUsage().getPromptTokens();
            Integer completionTokens=response.getMetadata().getUsage()==null?null:response.getMetadata().getUsage().getCompletionTokens();
            totalPromptTokens+=promptTokens==null?0:promptTokens; totalCompletionTokens+=completionTokens==null?0:completionTokens;
            if(totalPromptTokens+totalCompletionTokens>(Integer)row[5]) throw new IllegalStateException("Agent exceeded its configured token budget");
            if(!response.hasToolCalls()) break;
            int callCount=response.getResult().getOutput().getToolCalls().size();
            if(callCount<1||callCount>(Integer)row[4]-totalToolCalls) throw new IllegalStateException("Agent exceeded its configured tool-call limit");
            totalToolCalls+=callCount;
            ensureTimeRemaining(deadline);
            var toolResult=toolCallingManager.executeToolCalls(prompt,response);
            ensureTimeRemaining(deadline);
            prompt=new Prompt(toolResult.conversationHistory(),options);
            response=callModel(model,prompt,deadline);
        }
        String answer=response.getResult()==null?null:response.getResult().getOutput().getText();
        if(answer==null||answer.isBlank()) throw new IllegalStateException("AI provider returned no usable response");
        return new Result(answer,(String)row[2],totalPromptTokens,totalCompletionTokens);
    }

    private ChatResponse callModel(ChatModel model,Prompt prompt,long deadline){
        long remaining=deadline-System.nanoTime();
        if(remaining<=0) throw new IllegalStateException("Agent exceeded its configured timeout");
        CompletableFuture<ChatResponse> call=CompletableFuture.supplyAsync(()->model.call(prompt),providerExecutor);
        try{return call.get(remaining,TimeUnit.NANOSECONDS);}
        catch(TimeoutException ex){call.cancel(true);throw new IllegalStateException("AI provider request exceeded agent timeout");}
        catch(InterruptedException ex){call.cancel(true);Thread.currentThread().interrupt();throw new IllegalStateException("AI provider request interrupted",ex);}
        catch(ExecutionException ex){Throwable cause=ex.getCause();if(cause instanceof RuntimeException runtime)throw runtime;throw new IllegalStateException("AI provider request failed",cause);}
    }
    private static void ensureTimeRemaining(long deadline){if(System.nanoTime()>=deadline)throw new IllegalStateException("Agent exceeded its configured timeout");}
}
