package config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * QQ邮箱 IMAP/SMTP 配置。
 * application.yml 中以 email.* 为前缀。
 */
@ConfigurationProperties(prefix = "email")
public class EmailConfig {

    private Imap imap = new Imap();
    private Smtp smtp = new Smtp();
    private String username;
    private String password;

    public Imap getImap() { return imap; }
    public void setImap(Imap imap) { this.imap = imap; }

    public Smtp getSmtp() { return smtp; }
    public void setSmtp(Smtp smtp) { this.smtp = smtp; }

    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }

    public String getPassword() { return password; }
    public void setPassword(String password) { this.password = password; }

    public static class Imap {
        private String host;
        private int port;
        public String getHost() { return host; }
        public void setHost(String host) { this.host = host; }
        public int getPort() { return port; }
        public void setPort(int port) { this.port = port; }
    }

    public static class Smtp {
        private String host;
        private int port;
        public String getHost() { return host; }
        public void setHost(String host) { this.host = host; }
        public int getPort() { return port; }
        public void setPort(int port) { this.port = port; }
    }
}
