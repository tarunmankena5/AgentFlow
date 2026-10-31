package com.agentflow.tool;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

/** Safe helper for key/value text; semantic extraction remains the model's responsibility. */
@Component
public class StructuredExtractTool {
    @Tool(description = "Extract requested key:value fields from text. Returns only explicit matches; missing fields are null.")
    public Map<String,String> extract(@ToolParam(description="Text to inspect, maximum 10000 characters") String text,
                                      @ToolParam(description="Field names to extract") List<String> fields) {
        if(text==null||text.length()>10000||fields==null||fields.size()>50) throw new IllegalArgumentException("Input exceeds limits");
        Map<String,String> result=new LinkedHashMap<>();
        for(String field:fields) {
            if(field==null||field.length()>100) throw new IllegalArgumentException("Invalid field name");
            String found=null;
            for(String line:text.split("\\R")) { int colon=line.indexOf(':'); if(colon>0&&line.substring(0,colon).trim().equalsIgnoreCase(field)) { found=line.substring(colon+1).trim(); break; } }
            result.put(field,found);
        }
        return result;
    }
}
