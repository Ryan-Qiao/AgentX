package com.kama.jchatmind.agent.tools.test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kama.jchatmind.agent.tools.Tool;
import com.kama.jchatmind.agent.tools.ToolType;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.List;

@Slf4j
@Component
public class WeatherTool implements Tool {
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(8);
    private static final String GEOCODING_ENDPOINT = "https://geocoding-api.open-meteo.com/v1/search";
    private static final String FORECAST_ENDPOINT = "https://api.open-meteo.com/v1/forecast";

    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    public WeatherTool(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(REQUEST_TIMEOUT)
                .build();
    }

    @Override
    public String getName() {
        return "weatherTool";
    }

    @Override
    public String getDescription() {
        return "获取指定城市和日期的真实天气，仅在用户明确询问天气、气温、降雨等天气信息时使用。";
    }

    @Override
    public ToolType getType() {
        return ToolType.FIXED;
    }

    @org.springframework.ai.tool.annotation.Tool(name = "weather", description = "获取指定城市和日期的真实天气。仅在用户明确询问天气、气温、降雨、出行天气等天气信息时调用；不要在城市文章、城市报告、旅游介绍等普通写作任务中调用。")
    public String getWeather(String city, String date) {
        if (city == null || city.trim().isEmpty()) {
            return "错误：城市不能为空";
        }

        LocalDate targetDate;
        try {
            targetDate = (date == null || date.trim().isEmpty())
                    ? LocalDate.now()
                    : LocalDate.parse(date.trim());
        } catch (DateTimeParseException e) {
            return "错误：日期格式无效，请使用 yyyy-MM-dd，例如 2026-06-26";
        }

        try {
            Location location = resolveLocation(normalizeCityName(city));
            JsonNode daily = fetchDailyForecast(location, targetDate);
            int dateIndex = findDateIndex(daily.path("time"), targetDate);
            if (dateIndex < 0) {
                return "错误：天气服务暂不支持查询 " + targetDate + " 的预报，请查询未来 16 天内日期";
            }

            int weatherCode = daily.path("weather_code").path(dateIndex).asInt();
            double maxTemperature = daily.path("temperature_2m_max").path(dateIndex).asDouble();
            double minTemperature = daily.path("temperature_2m_min").path(dateIndex).asDouble();
            int precipitationProbability = daily.path("precipitation_probability_max").path(dateIndex).asInt();

            return "%s（%s）%s 天气：%s，气温 %.1f°C ~ %.1f°C，最高降水概率 %d%%。数据来源：Open-Meteo。"
                    .formatted(
                            location.name(),
                            location.country(),
                            targetDate,
                            describeWeatherCode(weatherCode),
                            minTemperature,
                            maxTemperature,
                            precipitationProbability
                    );
        } catch (Exception e) {
            log.warn("查询天气失败 city={}, date={}", city, date, e);
            return "错误：查询天气失败 - " + e.getMessage();
        }
    }

    private Location resolveLocation(String city) throws Exception {
        String uri = GEOCODING_ENDPOINT
                + "?name=" + URLEncoder.encode(city, StandardCharsets.UTF_8)
                + "&count=1&language=zh&format=json";
        JsonNode root = getJson(uri);
        JsonNode first = root.path("results").path(0);
        if (first.isMissingNode()) {
            throw new IllegalArgumentException("无法找到城市坐标：" + city);
        }
        return new Location(
                first.path("name").asText(city),
                first.path("country").asText("未知国家"),
                first.path("latitude").asDouble(),
                first.path("longitude").asDouble(),
                first.path("timezone").asText("auto")
        );
    }

    private String normalizeCityName(String city) {
        String normalized = city.trim();
        for (String separator : List.of("，", ",", "、", "/", "|")) {
            int index = normalized.indexOf(separator);
            if (index > 0) {
                normalized = normalized.substring(0, index).trim();
            }
        }
        return normalized;
    }

    private JsonNode fetchDailyForecast(Location location, LocalDate targetDate) throws Exception {
        LocalDate today = LocalDate.now();
        long forecastDays = Math.max(1, Math.min(16, java.time.temporal.ChronoUnit.DAYS.between(today, targetDate) + 1));
        String timezone = URLEncoder.encode(location.timezone(), StandardCharsets.UTF_8);
        String uri = FORECAST_ENDPOINT
                + "?latitude=" + location.latitude()
                + "&longitude=" + location.longitude()
                + "&daily=weather_code,temperature_2m_max,temperature_2m_min,precipitation_probability_max"
                + "&timezone=" + timezone
                + "&forecast_days=" + forecastDays;
        return getJson(uri).path("daily");
    }

    private JsonNode getJson(String uri) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(uri))
                .timeout(REQUEST_TIMEOUT)
                .header("User-Agent", "JChatMind/1.0")
                .GET()
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IllegalStateException("天气服务返回 HTTP " + response.statusCode());
        }
        return objectMapper.readTree(response.body());
    }

    private int findDateIndex(JsonNode times, LocalDate targetDate) {
        String expected = targetDate.toString();
        for (int i = 0; i < times.size(); i++) {
            if (expected.equals(times.path(i).asText())) {
                return i;
            }
        }
        return -1;
    }

    private String describeWeatherCode(int code) {
        return switch (code) {
            case 0 -> "晴";
            case 1, 2, 3 -> "多云";
            case 45, 48 -> "雾";
            case 51, 53, 55, 56, 57 -> "毛毛雨";
            case 61, 63, 65, 66, 67 -> "雨";
            case 71, 73, 75, 77 -> "雪";
            case 80, 81, 82 -> "阵雨";
            case 85, 86 -> "阵雪";
            case 95 -> "雷暴";
            case 96, 99 -> "雷暴伴冰雹";
            default -> "未知天气代码 " + code;
        };
    }

    private record Location(String name, String country, double latitude, double longitude, String timezone) {
    }
}
