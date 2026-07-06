package io.pigagent.core.agent.runner;

import io.pigagent.core.agent.AgentSpec;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class FileReportWriterTest {

    @Test
    void writesReport_underDateDir(@TempDir Path reports) throws java.io.IOException {
        FileReportWriter writer = new FileReportWriter(reports);
        AgentSpec spec = AgentSpec.create("nightwatch", "代码守夜人");
        AgentReport report = new AgentReport("nightwatch", "代码守夜人",
                AgentReport.Outcome.SUCCESS, "跑完测试", List.of("executeCommand：rm -rf"), "");

        writer.write(spec, report);

        Path file = reports.resolve(LocalDate.now().toString()).resolve("nightwatch.md");
        assertThat(Files.exists(file)).isTrue();
        String content = Files.readString(file);
        assertThat(content).contains("我做了").contains("跑完测试").contains("等你决定").contains("rm -rf");
    }
}
