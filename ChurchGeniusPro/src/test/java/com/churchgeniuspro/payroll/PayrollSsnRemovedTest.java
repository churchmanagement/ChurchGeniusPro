package com.churchgeniuspro.payroll;

import com.churchgeniuspro.payroll.controller.dto.EmployeeRequest;
import com.churchgeniuspro.payroll.entity.PayrollEmployee;
import com.churchgeniuspro.payroll.service.SsnDataPurge;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * SSN is not stored (user decision 2026-11-06): no field on the employee, no field on the request,
 * no encryption code, and the start-up purge clears any value left in the old column.
 */
@DisplayName("Payroll — SSN is not stored and old values are cleared")
class PayrollSsnRemovedTest {

    @Test @DisplayName("entity and request carry no SSN field")
    void noSsnFields() {
        for (Class<?> c : List.of(PayrollEmployee.class, EmployeeRequest.class)) {
            assertThat(Arrays.stream(c.getDeclaredFields()).map(Field::getName).map(String::toLowerCase))
                    .as(c.getSimpleName()).noneMatch(n -> n.contains("ssn"));
        }
    }

    @Test @DisplayName("an old client sending ssnLast4 is accepted and the value goes nowhere")
    void oldClientPayloadIgnored() throws Exception {
        // strict mapper: the class itself must ignore the unknown property
        ObjectMapper strict = new ObjectMapper().enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        EmployeeRequest r = strict.readValue("{\"firstName\":\"Jamie\",\"lastName\":\"Rivera\",\"ssnLast4\":\"4321\"}", EmployeeRequest.class);
        assertThat(r.getFirstName()).isEqualTo("Jamie");
        assertThat(r.toString()).doesNotContain("4321");
    }

    @Test @DisplayName("purge clears the column when it exists and reports the count")
    void purgeClears() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForObject(anyString(), eq(Integer.class))).thenReturn(1);
        when(jdbc.update("UPDATE payroll_employee SET ssn_last4 = NULL WHERE ssn_last4 IS NOT NULL")).thenReturn(3, 0);
        SsnDataPurge p = new SsnDataPurge(jdbc);
        assertThat(p.purge()).isEqualTo(3);
        assertThat(p.purge()).isZero();           // idempotent
    }

    @Test @DisplayName("purge skips a database without the column and never throws")
    void purgeSkipsAndNeverThrows() {
        JdbcTemplate none = mock(JdbcTemplate.class);
        when(none.queryForObject(anyString(), eq(Integer.class))).thenReturn(0);
        assertThat(new SsnDataPurge(none).purge()).isEqualTo(-1);
        verify(none, never()).update(anyString());

        JdbcTemplate broken = mock(JdbcTemplate.class);
        when(broken.queryForObject(anyString(), eq(Integer.class))).thenThrow(new RuntimeException("db down"));
        assertThat(new SsnDataPurge(broken).purge()).isEqualTo(-1);
    }

    @Test @DisplayName("main code has no SSN encryption, field or key left")
    void sourceScan() throws IOException {
        Path root = Path.of("src/main/java/com/churchgeniuspro");
        try (Stream<Path> files = Files.walk(root)) {
            for (Path f : files.filter(x -> x.toString().endsWith(".java")).toList()) {
                if (f.getFileName().toString().equals("SsnDataPurge.java")) continue;
                String s = Files.readString(f);
                assertThat(s).as(f.toString())
                        .doesNotContain("SsnCrypto").doesNotContain("SsnRotation")
                        .doesNotContain("setSsnLast4").doesNotContain("getSsnLast4")
                        .doesNotContain("PAYROLL_SSN_ENC_KEY\"");
            }
        }
        assertThat(Files.readString(Path.of("src/main/resources/application.properties")))
                .doesNotContain("payroll.ssn-enc-key=");
    }
}
