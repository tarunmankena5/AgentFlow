package com.agentflow.tool;

import com.agentflow.identity.CurrentUser;
import com.agentflow.organization.OrganizationController;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.util.*;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController @RequestMapping("/api/v1/organizations/{organizationId}/tools")
public class ToolController {
    private final JdbcTemplate jdbc; private final CalculatorTool calculator; private final DateTimeTool dateTime; private final StructuredExtractTool extract; private final ObjectMapper json;
    public ToolController(JdbcTemplate jdbc,CalculatorTool calculator,DateTimeTool dateTime,StructuredExtractTool extract,ObjectMapper json){this.jdbc=jdbc;this.calculator=calculator;this.dateTime=dateTime;this.extract=extract;this.json=json;}
    public record ToolView(UUID id,String name,String description,JsonNode inputSchema,boolean requiresApproval) { }
    public record ExecuteRequest(@NotNull Map<String,Object> input) { }
    @GetMapping public List<ToolView> list(@PathVariable UUID organizationId,@AuthenticationPrincipal CurrentUser user) {
        OrganizationController.requireRole(jdbc,organizationId,user.id(),"ORGANIZATION_ADMIN","WORKSPACE_MANAGER","DEVELOPER","OPERATOR","VIEWER");
        return jdbc.query("SELECT id,name,description,input_schema::text,requires_approval FROM tools WHERE enabled=true AND (organization_id=? OR organization_id IS NULL) ORDER BY organization_id NULLS FIRST,name",(rs,n)->{ try { return new ToolView(rs.getObject(1,UUID.class),rs.getString(2),rs.getString(3),json.readTree(rs.getString(4)),rs.getBoolean(5)); } catch(java.io.IOException e) { throw new IllegalStateException("Invalid stored tool schema",e); } },organizationId);
    }
    @PostMapping("/{toolName}/execute") public Object execute(@PathVariable UUID organizationId,@PathVariable String toolName,@AuthenticationPrincipal CurrentUser user,@Valid @RequestBody ExecuteRequest request) {
        OrganizationController.requireRole(jdbc,organizationId,user.id(),"ORGANIZATION_ADMIN","WORKSPACE_MANAGER","DEVELOPER","OPERATOR");
        var rows=jdbc.query("SELECT id,requires_approval FROM tools WHERE name=? AND enabled=true AND (organization_id=? OR organization_id IS NULL)",(rs,n)->new Object[]{rs.getObject(1,UUID.class),rs.getBoolean(2)},toolName,organizationId);
        if(rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Tool not found");
        if((Boolean)rows.get(0)[1]) throw new ResponseStatusException(HttpStatus.CONFLICT,"This tool requires an approved workflow step");
        Map<String,Object> in=request.input();
        try { return switch(toolName) {
            case "calculator" -> Map.of("result",calculator.calculate(string(in,"expression")));
            case "date_time" -> Map.of("dateTime",dateTime.currentDateTime(Objects.toString(in.getOrDefault("timezone","UTC"))));
            case "structured_extract" -> extract.extract(string(in,"text"),stringList(in,"fields"));
            case "document_search" -> new DocumentSearchTool(jdbc,organizationId).search(string(in,"query"));
            default -> throw new ResponseStatusException(HttpStatus.NOT_IMPLEMENTED,"Tool is available to assigned agents only");
        }; } catch(IllegalArgumentException ex) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Tool input is invalid"); }
    }
    private static String string(Map<String,Object> in,String key){Object v=in.get(key);if(!(v instanceof String s)||s.isBlank())throw new IllegalArgumentException();return s;}
    private static List<String> stringList(Map<String,Object> in,String key){Object v=in.get(key);if(!(v instanceof List<?> l)||l.size()>50||l.stream().anyMatch(x->!(x instanceof String)))throw new IllegalArgumentException();return l.stream().map(String.class::cast).toList();}
}
