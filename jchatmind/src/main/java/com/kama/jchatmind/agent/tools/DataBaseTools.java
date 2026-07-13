package com.kama.jchatmind.agent.tools;

import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

@Component
@Slf4j
public class DataBaseTools implements Tool {
    private static final int MAX_ROWS = 100;
    private static final int QUERY_TIMEOUT_SECONDS = 10;
    private static final int MAX_CELL_CHARS = 2_000;
    private static final Pattern MUTATING_KEYWORDS = Pattern.compile(
            "\\b(INSERT|UPDATE|DELETE|DROP|ALTER|CREATE|TRUNCATE|MERGE|GRANT|REVOKE|CALL|COPY|VACUUM|ANALYZE|REFRESH|REINDEX|LOCK)\\b",
            Pattern.CASE_INSENSITIVE
    );
    private static final Pattern COMMENT_PATTERN = Pattern.compile("(--|/\\*|\\*/)");
    private static final Pattern DANGEROUS_READ_PATTERN = Pattern.compile(
            "\\b(PG_SLEEP|PG_READ_FILE|PG_READ_BINARY_FILE|PG_LS_DIR|PG_STAT_FILE|LO_IMPORT|LO_EXPORT|DBLINK|CURRENT_SETTING)\\s*\\(|\\b(PG_CATALOG|INFORMATION_SCHEMA)\\.",
            Pattern.CASE_INSENSITIVE
    );

    private final JdbcTemplate jdbcTemplate;

    public DataBaseTools(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public String getName() {
        return "dataBaseTool";
    }

    @Override
    public String getDescription() {
        return "一个用于执行数据库查询操作的工具，主要用于从 PostgreSQL 中读取数据。";
    }

    @Override
    public ToolType getType() {
        return ToolType.OPTIONAL;
    }

    /**
     * 执行一条 SQL 查询，从数据库中进行查询数据
     *
     * @param sql SQL 查询语句（仅支持 SELECT 查询）
     * @return 格式化的查询结果字符串
     */
    @org.springframework.ai.tool.annotation.Tool(name = "databaseQuery", description = "用于在 PostgreSQL 中执行只读查询（SELECT）。接收由模型生成的查询语句，并返回结构化数据结果。该工具仅用于检索数据，严禁任何写入或修改数据库的语句。")
    @Transactional(readOnly = true, timeout = QUERY_TIMEOUT_SECONDS)
    public String query(String sql) {
        try {
            String validationError = validateReadOnlySql(sql);
            if (validationError != null) {
                log.warn("拒绝执行 SQL: {}, reason={}", sql, validationError);
                return validationError + "\nSQL: " + sql;
            }

            List<String> rows = jdbcTemplate.execute((Statement statement) -> {
                statement.setMaxRows(MAX_ROWS);
                statement.setQueryTimeout(QUERY_TIMEOUT_SECONDS);
                try (ResultSet rs = statement.executeQuery(sql.trim())) {
                    return formatResultSet(rs);
                }
            });

            if (rows == null) {
                return "错误：查询未返回结果";
            }

            int dataRowCount = rows.size() - 2; // 减去表头和分隔线
            if (rows.size() > 2 && rows.get(rows.size() - 1).contains("(无数据)")) {
                dataRowCount = 0;
            }

            log.info("成功执行 SQL 查询，返回 {} 行数据", dataRowCount);
            String suffix = dataRowCount >= MAX_ROWS ? "\n注意：结果已限制为最多 " + MAX_ROWS + " 行。" : "";
            return "查询结果:\n" + String.join("\n", rows) + suffix;
        } catch (Exception e) {
            log.error("未知错误: {}", e.getMessage(), e);
            return "错误：操作失败 - " + e.getMessage() + "\nSQL: " + sql;
        }
    }

    private String validateReadOnlySql(String sql) {
        if (sql == null || sql.trim().isEmpty()) {
            return "错误：SQL 不能为空";
        }

        String trimmedSql = sql.trim();
        if (COMMENT_PATTERN.matcher(trimmedSql).find()) {
            return "错误：SQL 中不允许包含注释";
        }

        String withoutTrailingSemicolon = trimmedSql.endsWith(";")
                ? trimmedSql.substring(0, trimmedSql.length() - 1).trim()
                : trimmedSql;
        if (withoutTrailingSemicolon.contains(";")) {
            return "错误：仅允许执行单条 SELECT 查询";
        }

        String upperSql = withoutTrailingSemicolon.toUpperCase(Locale.ROOT);
        if (!upperSql.startsWith("SELECT")) {
            return "错误：仅支持 SELECT 查询语句";
        }

        if (MUTATING_KEYWORDS.matcher(upperSql).find()) {
            return "错误：SQL 中包含写入或管理类关键字，仅允许只读查询";
        }
        if (DANGEROUS_READ_PATTERN.matcher(withoutTrailingSemicolon).find()) {
            return "错误：SQL 包含禁止访问的系统函数或系统目录";
        }

        return null;
    }

    private List<String> formatResultSet(ResultSet rs) throws java.sql.SQLException {
        List<String> resultRows = new ArrayList<>();
        ResultSetMetaData metaData = rs.getMetaData();
        int columnCount = metaData.getColumnCount();

        if (columnCount == 0) {
            resultRows.add("查询结果为空（无列）");
            return resultRows;
        }

        List<String> columnNames = new ArrayList<>();
        List<Integer> columnWidths = new ArrayList<>();
        for (int i = 1; i <= columnCount; i++) {
            String columnName = metaData.getColumnName(i);
            columnNames.add(columnName);
            columnWidths.add(columnName.length());
        }

        List<List<String>> dataRows = new ArrayList<>();
        while (rs.next()) {
            List<String> rowData = new ArrayList<>();
            for (int i = 1; i <= columnCount; i++) {
                Object value = rs.getObject(i);
                String valueStr = value == null ? "NULL" : value.toString();
                if (valueStr.length() > MAX_CELL_CHARS) {
                    valueStr = valueStr.substring(0, MAX_CELL_CHARS) + "…[已截断]";
                }
                rowData.add(valueStr);
                int currentWidth = columnWidths.get(i - 1);
                if (valueStr.length() > currentWidth) {
                    columnWidths.set(i - 1, valueStr.length());
                }
            }
            dataRows.add(rowData);
        }

        StringBuilder header = new StringBuilder("| ");
        for (int i = 0; i < columnCount; i++) {
            String columnName = columnNames.get(i);
            int width = columnWidths.get(i);
            header.append(String.format("%-" + width + "s", columnName)).append(" | ");
        }
        resultRows.add(header.toString());

        StringBuilder separator = new StringBuilder("|");
        for (int i = 0; i < columnCount; i++) {
            int width = columnWidths.get(i);
            separator.append("-".repeat(width + 2)).append("|");
        }
        resultRows.add(separator.toString());

        if (dataRows.isEmpty()) {
            StringBuilder emptyRow = new StringBuilder("| ");
            int totalWidth = columnWidths.stream().mapToInt(w -> w + 3).sum() - 1;
            emptyRow.append(String.format("%-" + (totalWidth - 2) + "s", "(无数据)"));
            emptyRow.append(" |");
            resultRows.add(emptyRow.toString());
        } else {
            for (List<String> rowData : dataRows) {
                StringBuilder row = new StringBuilder("| ");
                for (int i = 0; i < columnCount; i++) {
                    String value = rowData.get(i);
                    int width = columnWidths.get(i);
                    row.append(String.format("%-" + width + "s", value)).append(" | ");
                }
                resultRows.add(row.toString());
            }
        }

        return resultRows;
    }
}
