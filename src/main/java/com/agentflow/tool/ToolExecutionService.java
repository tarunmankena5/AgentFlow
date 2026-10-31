package com.agentflow.tool;

import com.agentflow.shared.config.AgentFlowProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class ToolExecutionService {
    private final JdbcTemplate jdbc; private final CalculatorTool calculator; private final DateTimeTool dateTime; private final StructuredExtractTool extract; private final AgentFlowProperties properties; private final ObjectMapper json;
    public ToolExecutionService(JdbcTemplate jdbc,CalculatorTool calculator,DateTimeTool dateTime,StructuredExtractTool extract,AgentFlowProperties properties,ObjectMapper json){this.jdbc=jdbc;this.calculator=calculator;this.dateTime=dateTime;this.extract=extract;this.properties=properties;this.json=json;}
    public record Result(UUID toolId,String name,Object output) { }
    public Result execute(UUID organizationId,UUID toolId,Map<String,Object> input,boolean approved) {
        var rows=jdbc.query("SELECT id,name,requires_approval FROM tools WHERE id=? AND enabled=true AND (organization_id=? OR organization_id IS NULL)",(rs,n)->new Object[]{rs.getObject(1,UUID.class),rs.getString(2),rs.getBoolean(3)},toolId,organizationId);
        if(rows.isEmpty()) throw new IllegalArgumentException("Tool is unavailable in this organization"); Object[] row=rows.get(0); String name=(String)row[1];
        if((Boolean)row[2]&&!approved) throw new IllegalStateException("Tool requires approval");
        Object output=switch(name) {
            case "calculator" -> Map.of("result",calculator.calculate(required(input,"expression")));
            case "date_time" -> Map.of("dateTime",dateTime.currentDateTime(Objects.toString(input.getOrDefault("timezone","UTC"))));
            case "structured_extract" -> extract.extract(required(input,"text"),strings(input,"fields"));
            case "document_search" -> new DocumentSearchTool(jdbc,organizationId).search(required(input,"query"));
            case "http_api" -> parseHttp(new HttpApiTool(properties,json).request(required(input,"url"),required(input,"method"),object(input,"body")));
            default -> throw new IllegalArgumentException("Tool has no executor");
        };
        return new Result((UUID)row[0],name,output);
    }
    private static Object parseHttp(String response){return Map.of("response",response);}
    private static Map<String,Object> object(Map<String,Object> input,String key){Object value=input.get(key);if(value==null)return Map.of();if(!(value instanceof Map<?,?> map))throw new IllegalArgumentException("Invalid object field: "+key);Map<String,Object> result=new LinkedHashMap<>();for(var entry:map.entrySet()){if(!(entry.getKey() instanceof String name))throw new IllegalArgumentException("Invalid object key");result.put(name,entry.getValue());}return result;}
    private static String required(Map<String,Object> input,String key){Object v=input.get(key);if(!(v instanceof String s)||s.isBlank())throw new IllegalArgumentException("Missing string field: "+key);return s;}
    private static List<String> strings(Map<String,Object> input,String key){Object v=input.get(key);if(!(v instanceof List<?> list)||list.size()>50||list.stream().anyMatch(x->!(x instanceof String)))throw new IllegalArgumentException("Invalid list field: "+key);return list.stream().map(String.class::cast).toList();}
}
