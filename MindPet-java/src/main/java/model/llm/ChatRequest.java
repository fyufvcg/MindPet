package model.llm;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import java.util.Map;

@JsonInclude(JsonInclude.Include.NON_NULL)
public class ChatRequest {
    private String model;
    private int maxTokens = 4000;
    private double temperature = 0.8;
    private List<Map<String, Object>> messages;
    private Object tools;

    public String getModel() { return model; }
    public void setModel(String model) { this.model = model; }

    @JsonProperty("max_tokens")
    public int getMaxTokens() { return maxTokens; }
    public void setMaxTokens(int maxTokens) { this.maxTokens = maxTokens; }

    public double getTemperature() { return temperature; }
    public void setTemperature(double temperature) { this.temperature = temperature; }

    public List<Map<String, Object>> getMessages() { return messages; }
    public void setMessages(List<Map<String, Object>> messages) { this.messages = messages; }

    public Object getTools() { return tools; }
    public void setTools(Object tools) { this.tools = tools; }
}
