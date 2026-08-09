package tool.impl;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;
import util.HttpJsonUtil;

import java.math.BigDecimal;
import java.math.RoundingMode;

@Component
public class ExchangeRateTool {

    @Tool(description = "Convert currencies")
    public String convertCurrency(
            @ToolParam(description = "Amount") BigDecimal amount,
            @ToolParam(description = "Source currency, e.g. USD, CNY") String fromCurrency,
            @ToolParam(description = "Target currency, e.g. USD, CNY") String toCurrency) {

        String from = fromCurrency != null ? fromCurrency.trim().toUpperCase() : null;
        String to = toCurrency != null ? toCurrency.trim().toUpperCase() : null;
        BigDecimal amt = amount != null ? amount : BigDecimal.ONE;

        if (from == null || to == null || amt == null) {
            return "Error: missing parameters";
        }
        try {
            JsonNode root = HttpJsonUtil.getJson("https://open.er-api.com/v6/latest/" + from);
            if (!"success".equals(root.path("result").asText())) {
                return "Exchange rate lookup failed";
            }
            JsonNode rates = root.path("rates");
            if (rates.isMissingNode() || !rates.has(to)) {
                return "Rate not found: " + from + " -> " + to;
            }
            BigDecimal rate = rates.get(to).decimalValue();
            BigDecimal converted = amt.multiply(rate).setScale(4, RoundingMode.HALF_UP);
            return amt.stripTrailingZeros().toPlainString() + " " + from + " = " + converted.toPlainString() + " " + to + "\nRate: " + rate.toPlainString();
        } catch (Exception e) {
            return "Currency conversion failed: " + e.getMessage();
        }
    }
}
