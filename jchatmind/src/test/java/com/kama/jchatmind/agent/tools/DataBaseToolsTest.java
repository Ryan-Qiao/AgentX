package com.kama.jchatmind.agent.tools;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DataBaseToolsTest {

    @Test
    void queryRejectsMutatingSqlBeforeDatabaseExecution() {
        DataBaseTools tools = new DataBaseTools(null);

        String result = tools.query("DELETE FROM agent;");

        assertThat(result).contains("仅支持 SELECT 查询语句");
        assertThat(result).contains("DELETE FROM agent;");
    }
}
