package com.agentflow.tool;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

/** Small arithmetic grammar; expressions never reach a scripting engine. */
@Component
public class CalculatorTool {
    @Tool(description = "Evaluate a basic arithmetic expression using parentheses and + - * /.")
    public double calculate(@ToolParam(description = "Arithmetic expression, maximum 200 characters") String expression) {
        if (expression == null || expression.length() > 200) throw new IllegalArgumentException("Expression is too long");
        Parser p = new Parser(expression); double value = p.expression(); p.space();
        if (!p.end()) throw new IllegalArgumentException("Unexpected character in expression");
        if (!Double.isFinite(value)) throw new IllegalArgumentException("Result is not finite");
        return value;
    }
    private static final class Parser {
        private final String s; private int i;
        Parser(String s) { this.s=s; }
        boolean end() { return i==s.length(); }
        void space() { while(i<s.length() && Character.isWhitespace(s.charAt(i))) i++; }
        double expression() { double v=term(); while(true) { space(); if(take('+')) v+=term(); else if(take('-')) v-=term(); else return v; } }
        double term() { double v=factor(); while(true) { space(); if(take('*')) v*=factor(); else if(take('/')) { double d=factor(); if(d==0) throw new IllegalArgumentException("Division by zero"); v/=d; } else return v; } }
        double factor() { space(); if(take('+')) return factor(); if(take('-')) return -factor(); if(take('(')) { double v=expression(); space(); if(!take(')')) throw new IllegalArgumentException("Missing closing parenthesis"); return v; } int start=i; while(i<s.length() && (Character.isDigit(s.charAt(i))||s.charAt(i)=='.')) i++; if(start==i) throw new IllegalArgumentException("Expected a number"); try{return Double.parseDouble(s.substring(start,i));}catch(NumberFormatException e){throw new IllegalArgumentException("Invalid number");} }
        boolean take(char c) { if(i<s.length()&&s.charAt(i)==c){i++;return true;} return false; }
    }
}
