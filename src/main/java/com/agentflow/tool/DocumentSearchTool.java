package com.agentflow.tool;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.jdbc.core.JdbcTemplate;

/** Organization-bound search prevents tool arguments from selecting another tenant. */
public class DocumentSearchTool {
    private final JdbcTemplate jdbc; private final UUID organizationId;
    public DocumentSearchTool(JdbcTemplate jdbc,UUID organizationId){this.jdbc=jdbc;this.organizationId=organizationId;}
    @Tool(description="Search text chunks in documents belonging to the current organization.")
    public List<Map<String,Object>> search(@ToolParam(description="Search phrase") String query) {
        if(query==null||query.isBlank()||query.length()>500) throw new IllegalArgumentException("Invalid search query");
        String pattern="%"+query.replace("!","!!").replace("%","!%").replace("_","!_")+"%";
        return jdbc.query("SELECT document_id,chunk_index,left(content,1500) AS content FROM document_chunks WHERE organization_id=? AND content ILIKE ? ESCAPE '!' ORDER BY chunk_index LIMIT 5",(rs,n)->Map.of("documentId",rs.getObject(1,UUID.class),"chunk",rs.getInt(2),"content",rs.getString(3)),organizationId,pattern);
    }
}
