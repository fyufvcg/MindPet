package tool.impl;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import service.BaiduTTSService;
import service.GeneratedAudioService;
import tool.ToolUserContext;

@Component
public class SpeakTextTool {

    private final BaiduTTSService ttsService;
    private final GeneratedAudioService generatedAudioService;

    @Autowired
    public SpeakTextTool(BaiduTTSService ttsService, GeneratedAudioService generatedAudioService) {
        this.ttsService = ttsService;
        this.generatedAudioService = generatedAudioService;
    }

    @Tool(description = "将指定文字用语音发送给用户。当用户要求用语音回复、念一段文字、或者希望用语音表达时调用。")
    public String speakText(
            @ToolParam(description = "需要合成为语音并发送的文字内容，不超过300字") String text) {
        if (text == null || text.isBlank()) {
            return "错误：未指定要合成的文字";
        }

        String userId = ToolUserContext.get();
        if (userId == null || userId.isBlank()) {
            return "语音生成失败：当前没有可用的用户上下文";
        }

        if (!ttsService.isConfigured()) {
            return "语音发送失败：TTS 服务未配置";
        }

        try {
            byte[] mp3Data = ttsService.synthesize(text);
            if (mp3Data == null || mp3Data.length == 0) {
                return "语音发送失败：合成结果为空";
            }
            GeneratedAudioService.AudioArtifact artifact = generatedAudioService.saveMp3(mp3Data);
            ToolUserContext.addGeneratedFile(
                artifact.path().toString(), artifact.fileName(), "audio/mpeg", null
            );
            return "语音已生成并附在本次回复中。";
        } catch (Exception e) {
            return "语音发送失败：" + e.getMessage();
        }
    }
}
