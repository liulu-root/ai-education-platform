package com.xiaomi.education.tutor;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import java.sql.DriverManager;
import static org.assertj.core.api.Assertions.assertThat;

class TutorContextMigrationTest {
    @Test void upgradesExistingMessagesWithTiedTimestampsWithoutChangingContent() throws Exception {
        var url = "jdbc:h2:mem:context-migration;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE";
        Flyway.configure().dataSource(url, "sa", "").target("6").load().migrate();
        try (var connection = DriverManager.getConnection(url, "sa", ""); var sql = connection.createStatement()) {
            sql.execute("INSERT INTO tutor_conversation (id, tenant_id, learner_id, title, status) VALUES ('old', 'tenant-demo', 'learner-001', 'test', 'ACTIVE')");
            sql.execute("INSERT INTO tutor_message (id, conversation_id, role, content, created_at) VALUES ('b', 'old', 'ASSISTANT', '原回答', TIMESTAMP '2026-01-01 00:00:00'), ('a', 'old', 'USER', '原问题', TIMESTAMP '2026-01-01 00:00:00')");
        }
        Flyway.configure().dataSource(url, "sa", "").load().migrate();
        try (var connection = DriverManager.getConnection(url, "sa", ""); var sql = connection.createStatement()) {
            try (var rows = sql.executeQuery("SELECT id, message_seq, content FROM tutor_message WHERE conversation_id = 'old' ORDER BY message_seq")) {
                assertThat(rows.next()).isTrue();
                assertThat(rows.getString(1)).isEqualTo("a");
                assertThat(rows.getLong(2)).isEqualTo(1);
                assertThat(rows.getString(3)).isEqualTo("原问题");
                assertThat(rows.next()).isTrue();
                assertThat(rows.getLong(2)).isEqualTo(2);
                assertThat(rows.getString(3)).isEqualTo("原回答");
            }
            try (var rows = sql.executeQuery("SELECT next_message_seq FROM tutor_conversation WHERE id = 'old'")) {
                rows.next(); assertThat(rows.getLong(1)).isEqualTo(2);
            }
        }
    }
}
