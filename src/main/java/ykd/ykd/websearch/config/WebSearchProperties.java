package ykd.ykd.websearch.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "web-search")
public class WebSearchProperties {
    private boolean enabled = false;
    private String apiKey;
    private String endpoint =
            "https://open.feedcoopapi.com/search_api/web_search";

    private int maxResults = 5;

    private int timeoutSeconds = 15;
}
