package ykd.ykd.config;

import org.springframework.stereotype.Component;

/**
 * 运行时配置，存储通过 Web 面板输入的 API 密钥。
 */
@Component
public class RuntimeConfig {

    private volatile String deepseekApiKey;
    private volatile String agnesApiKey;
    private volatile String elevenlabsApiKey;
    private volatile boolean keysConfigured;

    public String getDeepseekApiKey() {
        return deepseekApiKey;
    }

    public void setDeepseekApiKey(String deepseekApiKey) {
        this.deepseekApiKey = deepseekApiKey;
        checkKeysConfigured();
    }

    public String getAgnesApiKey() {
        return agnesApiKey;
    }

    public void setAgnesApiKey(String agnesApiKey) {
        this.agnesApiKey = agnesApiKey;
        checkKeysConfigured();
    }

    public String getElevenlabsApiKey() {
        return elevenlabsApiKey;
    }

    public void setElevenlabsApiKey(String elevenlabsApiKey) {
        this.elevenlabsApiKey = elevenlabsApiKey;
        checkKeysConfigured();
    }

    public boolean isKeysConfigured() {
        return keysConfigured;
    }

    private void checkKeysConfigured() {
        keysConfigured = (deepseekApiKey != null && !deepseekApiKey.isBlank())
                && (agnesApiKey != null && !agnesApiKey.isBlank())
                && (elevenlabsApiKey != null && !elevenlabsApiKey.isBlank());
    }
}
