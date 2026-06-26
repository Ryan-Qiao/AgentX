package com.kama.jchatmind.agent.tools.test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kama.jchatmind.agent.tools.Tool;
import com.kama.jchatmind.agent.tools.ToolType;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

@Slf4j
@Component
public class CityTool implements Tool {
    private static final URI PRIMARY_GEOLOCATION_URI = URI.create("https://api.ip.sb/geoip");
    private static final URI SECONDARY_GEOLOCATION_URI = URI.create("https://ipapi.co/json/");
    private static final URI TERTIARY_GEOLOCATION_URI = URI.create(
            "http://ip-api.com/json/?fields=status,country,regionName,city,query,message&lang=zh-CN"
    );
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(5);

    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    public CityTool(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(REQUEST_TIMEOUT)
                .build();
    }

    @Override
    public String getName() {
        return "cityTool";
    }

    @Override
    public String getDescription() {
        return "获取用户当前所在城市，仅在用户明确询问当前位置、当前城市，或天气工具链需要当前位置时使用。";
    }

    @Override
    public ToolType getType() {
        return ToolType.FIXED;
    }

    @org.springframework.ai.tool.annotation.Tool(name = "getCity", description = "获取用户当前所在城市。仅在用户明确询问当前位置/当前城市，或用户要求查询当前所在地天气时调用；不要在普通文章、报告、总结任务中调用。")
    public String getCity() {
        try {
            return getCityFromIpSb();
        } catch (Exception e) {
            log.warn("主定位服务失败，尝试第二定位服务", e);
            try {
                return getCityFromIpApiCo();
            } catch (Exception secondaryError) {
                log.warn("第二定位服务失败，尝试第三定位服务", secondaryError);
                try {
                    return getCityFromIpApi();
                } catch (Exception tertiaryError) {
                    log.warn("第三定位服务失败", tertiaryError);
                    return "无法获取当前城市：" + tertiaryError.getMessage();
                }
            }
        }
    }

    private String getCityFromIpSb() throws Exception {
        JsonNode root = getJson(PRIMARY_GEOLOCATION_URI);
        String city = root.path("city").asText("");
        String region = root.path("region").asText("");
        String country = root.path("country").asText("");
        return formatLocation(city, region, country);
    }

    private String getCityFromIpApiCo() throws Exception {
        JsonNode root = getJson(SECONDARY_GEOLOCATION_URI);
        if (root.path("error").asBoolean(false)) {
            throw new IllegalStateException(root.path("reason").asText("定位服务返回失败"));
        }
        String city = root.path("city").asText("");
        String region = root.path("region").asText("");
        String country = root.path("country_name").asText("");
        return formatLocation(city, region, country);
    }

    private String getCityFromIpApi() throws Exception {
        JsonNode root = getJson(TERTIARY_GEOLOCATION_URI);
        if (!"success".equals(root.path("status").asText())) {
            throw new IllegalStateException(root.path("message").asText("定位服务返回失败"));
        }
        String city = root.path("city").asText("");
        String region = root.path("regionName").asText("");
        String country = root.path("country").asText("");
        return formatLocation(city, region, country);
    }

    private JsonNode getJson(URI uri) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(REQUEST_TIMEOUT)
                .header("User-Agent", "JChatMind/1.0")
                .GET()
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IllegalStateException("定位服务返回 HTTP " + response.statusCode());
        }
        return objectMapper.readTree(response.body());
    }

    private String formatLocation(String city, String region, String country) {
        if (city == null || city.isBlank()) {
            throw new IllegalStateException("定位结果中没有城市信息");
        }
        StringBuilder result = new StringBuilder(city);
        if (region != null && !region.isBlank() && !region.equals(city)) {
            result.append("，").append(region);
        }
        if (country != null && !country.isBlank() && !country.equals(city)) {
            result.append("，").append(country);
        }
        return result.toString();
    }
}
