package com.example.agentloop;

import com.anthropic.models.messages.Tool;

import java.util.LinkedHashMap;
import java.util.Map;

/** The {@code calculator} tool: evaluates an arithmetic expression with an {@link ExpressionEvaluator}. */
public final class CalculatorTool implements AgentTool {

    private static final Tool DEFINITION = Tool.builder()
            .name("calculator")
            .description("Evaluate an arithmetic expression and return the numeric result. "
                    + "Supports + - * / // % ** and parentheses. Use this for any math "
                    + "instead of computing in your head.")
            .inputSchema(Json.singleStringSchema("expression", "Arithmetic expression, e.g. '(8849 - 6190) * 2'"))
            .strict(true)
            .build();

    private final ExpressionEvaluator evaluator;

    public CalculatorTool(ExpressionEvaluator evaluator) {
        this.evaluator = evaluator;
    }

    @Override
    public Tool definition() {
        return DEFINITION;
    }

    /** Returns {@code {"expression": ..., "result": ...}}; whole numbers are printed without ".0". */
    @Override
    public String execute(Map<String, Object> input) {
        String expression = AgentTool.requireString(input, "expression");
        double value = evaluator.evaluate(expression);
        Object result = (value == Math.rint(value) && !Double.isInfinite(value)) ? (Object) (long) value : value;
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("expression", expression);
        out.put("result", result);
        return Json.toJson(out);
    }
}
