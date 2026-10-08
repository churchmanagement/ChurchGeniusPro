package com.churchgeniuspro.integration;

import com.churchgeniuspro.hibernate.ChurchEventImage;
import com.churchgeniuspro.repository.ChurchEventImageRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Database audit P7: the flyer lives in church_event_image, and church_event no longer carries the
 * image_data column at all — so the ordinary event queries cannot load it. Proven against a real
 * PostgreSQL. Requires Docker; runs on {@code verify}.
 */
@Testcontainers(disabledWithoutDocker = true)
@ActiveProfiles("it")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class ChurchEventImageIT {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired ChurchEventImageRepository imageRepo;
    @Autowired JdbcTemplate jdbc;

    @Test
    @DisplayName("church_event has image_present and NOT image_data; church_event_image exists")
    void schemaMovedTheImageOffTheEventRow() {
        assertThat(columnExists("church_event", "image_present")).isTrue();
        assertThat(columnExists("church_event", "image_data")).as("the big column is gone from the hot table").isFalse();
        assertThat(columnExists("church_event_image", "image_data")).isTrue();
    }

    @Test
    @DisplayName("findImageDataByEventId returns only the payload, and round-trips")
    void repositoryRoundTrips() {
        ChurchEventImage img = new ChurchEventImage();
        img.setEventId(987654);
        img.setAppClientId("CHR-IT");
        img.setImageData("data:image/png;base64,ZZZZ");
        imageRepo.save(img);

        assertThat(imageRepo.findImageDataByEventId(987654)).contains("data:image/png;base64,ZZZZ");
        assertThat(imageRepo.findImageDataByEventId(111222)).isEmpty();

        imageRepo.deleteByEventId(987654);
        assertThat(imageRepo.findByEventId(987654)).isEmpty();
    }

    private boolean columnExists(String table, String column) {
        Integer n = jdbc.queryForObject("SELECT count(*) FROM information_schema.columns "
                + "WHERE table_schema = current_schema() AND table_name = ? AND column_name = ?", Integer.class, table, column);
        return n != null && n > 0;
    }
}
