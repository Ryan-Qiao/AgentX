package com.kama.jchatmind.agent.tools.test;

import com.kama.jchatmind.agent.tools.Tool;
import com.kama.jchatmind.agent.tools.ToolType;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

@Component
public class DateTool implements Tool {
    private static final ZoneId DEFAULT_ZONE = ZoneId.of("Asia/Shanghai");
    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ISO_LOCAL_DATE;

    @Override
    public String getName() {
        return "dateTool";
    }

    @Override
    public String getDescription() {
        return "获取当前日期，仅在用户明确询问今天日期，或天气等实时工具需要日期参数时使用。";
    }

    @Override
    public ToolType getType() {
        return ToolType.FIXED;
    }

    @org.springframework.ai.tool.annotation.Tool(name = "getDate", description = "获取当前日期。仅在用户明确询问今天日期，或天气等实时工具需要日期参数时调用；不要在普通文章、报告、总结任务中调用。")
    public String getDate() {
        return LocalDate.now(DEFAULT_ZONE).format(DATE_FORMATTER);
    }
}
