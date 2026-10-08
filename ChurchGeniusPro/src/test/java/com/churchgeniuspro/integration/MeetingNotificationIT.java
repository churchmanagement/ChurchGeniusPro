package com.churchgeniuspro.integration;

import com.churchgeniuspro.hibernate.*;
import com.churchgeniuspro.repository.*;
import com.churchgeniuspro.service.MeetingService;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Send Meeting Notification against real PostgreSQL: the first notification of a
 * month (no subscription_usage row yet) with more than one recipient. Before the fix
 * the second recipient failed with "current transaction is aborted". Requires Docker.
 */
@Testcontainers(disabledWithoutDocker = true)
@ActiveProfiles("it")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE,
                properties = { "app.base-url=http://localhost",   // the "it" profile does not set it
                               "management.health.mail.enabled=false" })   // JavaMailSender is mocked below
class MeetingNotificationIT {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired MeetingService meetings;
    @Autowired MeetingRepository meetingRepo;
    @Autowired MeetingTypeRepository typeRepo;
    @Autowired FamilyRepository familyRepo;
    @Autowired FamilyMemberRepository memberRepo;
    @Autowired JdbcTemplate jdbc;
    @MockitoBean JavaMailSender mailSender;

    @Test
    void firstNotificationOfTheMonthWithTwoRecipients() {
        String cid = "IT-MTG-" + UUID.randomUUID().toString().substring(0, 8);
        when(mailSender.createMimeMessage()).thenAnswer(i -> new MimeMessage((jakarta.mail.Session) null));

        Family f = new Family(); f.setAppClientId(cid); f.setDeleteFlag(false); f.setInactive(false);
        f = familyRepo.saveAndFlush(f);
        for (String e : List.of("one@it.test", "two@it.test")) {
            FamilyMember m = new FamilyMember();
            m.setFamily(f); m.setFirstName(e); m.setLastName("Member"); m.setEmail(e);
            m.setMemberType("Member"); m.setAppClientId(cid); m.setDeleteFlag(false); m.setInactive(false);
            memberRepo.saveAndFlush(m);
        }
        MeetingType t = new MeetingType(); t.setTypeName("Prayer Meeting"); t.setAppClientId(cid); t.setDeleteFlag(false);
        t = typeRepo.saveAndFlush(t);
        Meeting mtg = new Meeting(); mtg.setAppClientId(cid); mtg.setMeetingType(t);
        mtg.setMeetingDate(LocalDate.now().plusDays(3)); mtg.setStartTime("19:00");
        mtg = meetingRepo.saveAndFlush(mtg);

        assertThat(jdbc.queryForObject("select count(*) from subscription_usage where client_id = ?", Long.class, cid)).isZero();

        Map<String, Object> r = meetings.sendManualNotification(mtg.getId(), List.of("Email"), List.of("Members"), cid);

        assertThat(r).containsEntry("emailsSent", 2).containsEntry("emailsBlocked", 0);
        verify(mailSender, times(2)).send(any(MimeMessage.class));
        // The month's usage row was created and counted both sends.
        assertThat(jdbc.queryForObject("select emails_sent from subscription_usage where client_id = ?", Integer.class, cid)).isEqualTo(2);
    }
}
