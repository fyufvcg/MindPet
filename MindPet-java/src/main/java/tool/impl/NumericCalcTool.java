package tool.impl;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;
import util.ExpressionEvaluator;

import java.math.BigDecimal;

@Component
public class NumericCalcTool {

    @Tool(description = "Evaluate arithmetic expressions")
    public String numericCalculate(
            @ToolParam(description = "Arithmetic expression, e.g. 1+2*(3-1)") String expression) {

        if (expression == null || expression.isEmpty()) {
            return "Error: missing expression";
        }
        try {
            double result = ExpressionEvaluator.evaluate(expression);
            return "Result: " + format(result);
        } catch (Exception e) {
            return "Calculation failed: " + e.getMessage();
        }
    }

    private String format(double value) {
        return BigDecimal.valueOf(value).stripTrailingZeros().toPlainString();
    }
}
