package tool.impl;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import service.BaiduTTSService;

@Component
public class ChangeVoiceTool {

    private final BaiduTTSService ttsService;

    @Autowired
    public ChangeVoiceTool(BaiduTTSService ttsService) {
        this.ttsService = ttsService;
    }

    @Tool(description = "切换语音合成的音色")
    public String changeVoice(
            @ToolParam(description = "音色编号：0=女声(普通), 1=男声(普通), 3=情感男声, 4=情感女声, 10=男童声, 11=女童声") String voice) {
        if (voice == null || voice.isEmpty()) {
            return "错误：未指定音色";
        }
        try {
            int voiceId = Integer.parseInt(voice);
            if (ttsService.setPerson(voiceId)) {
                return "已切换音色为：" + ttsService.getCurrentVoiceName();
            } else {
                return "无效的音色编号，可用音色：" + ttsService.getAvailableVoices();
            }
        } catch (NumberFormatException e) {
            return "音色编号格式错误";
        }
    }
}
