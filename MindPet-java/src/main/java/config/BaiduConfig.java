package config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "baidu")
public class BaiduConfig {
    private Speech speech = new Speech();

    public Speech getSpeech() { return speech; }
    public void setSpeech(Speech speech) { this.speech = speech; }

    public static class Speech {
        private Api api = new Api();
        private Tts tts = new Tts();

        public Api getApi() { return api; }
        public void setApi(Api api) { this.api = api; }
        public Tts getTts() { return tts; }
        public void setTts(Tts tts) { this.tts = tts; }

        public static class Api {
            private String key;
            private String secret;

            public String getKey() { return key; }
            public void setKey(String key) { this.key = key; }
            public String getSecret() { return secret; }
            public void setSecret(String secret) { this.secret = secret; }
        }

        public static class Tts {
            private int speed = 5;
            private int pitch = 5;
            private int volume = 5;
            private int person = 0;

            public int getSpeed() { return speed; }
            public void setSpeed(int speed) { this.speed = speed; }
            public int getPitch() { return pitch; }
            public void setPitch(int pitch) { this.pitch = pitch; }
            public int getVolume() { return volume; }
            public void setVolume(int volume) { this.volume = volume; }
            public int getPerson() { return person; }
            public void setPerson(int person) { this.person = person; }
        }
    }
}
