package com.churchgeniuspro.service;

import com.churchgeniuspro.hibernate.AutoReminder;
import com.churchgeniuspro.hibernate.AutoReminderTypes;
import com.churchgeniuspro.hibernate.ChurchEvent;
import com.churchgeniuspro.hibernate.EventReminder;
import com.churchgeniuspro.hibernate.Meeting;
import com.churchgeniuspro.hibernate.MeetingReminder;
import com.churchgeniuspro.hibernate.OneTimeReminder;
import com.churchgeniuspro.model.AutoReminderBO;
import com.churchgeniuspro.model.EventReminderBO;
import com.churchgeniuspro.model.MeetingReminderBO;
import com.churchgeniuspro.model.OneTimeReminderBO;
import com.churchgeniuspro.repository.AutoReminderRepository;
import com.churchgeniuspro.repository.AutoReminderTypesRepository;
import com.churchgeniuspro.repository.ChurchEventRepository;
import com.churchgeniuspro.repository.EventReminderRepository;
import com.churchgeniuspro.repository.MeetingReminderRepository;
import com.churchgeniuspro.repository.MeetingRepository;
import com.churchgeniuspro.repository.OneTimeReminderRepository;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * CRUD service for all four reminder types:
 * Event, Auto, One-Time, and Meeting reminders.
 */
@Service
public class ReminderService {

    private final EventReminderRepository    eventRepo;
    private final AutoReminderRepository     autoRepo;
    private final AutoReminderTypesRepository autoTypesRepo;
    private final OneTimeReminderRepository  oneTimeRepo;
    private final MeetingReminderRepository  meetingRepo;
    private final ChurchEventRepository      churchEventRepo;
    private final MeetingRepository          meetingRepository;

    public ReminderService(EventReminderRepository    eventRepo,
                           AutoReminderRepository     autoRepo,
                           AutoReminderTypesRepository autoTypesRepo,
                           OneTimeReminderRepository  oneTimeRepo,
                           MeetingReminderRepository  meetingRepo,
                           ChurchEventRepository      churchEventRepo,
                           MeetingRepository          meetingRepository) {
        this.eventRepo        = eventRepo;
        this.autoRepo         = autoRepo;
        this.autoTypesRepo    = autoTypesRepo;
        this.oneTimeRepo      = oneTimeRepo;
        this.meetingRepo      = meetingRepo;
        this.churchEventRepo  = churchEventRepo;
        this.meetingRepository = meetingRepository;
    }

    // ── Auto Reminder Types ───────────────────────────────────────────────

    /** Returns all reminder types ordered by ID — for populating the UI dropdown. */
    public List<Map<String, Object>> getAllAutoReminderTypes() {
        return autoTypesRepo.findAllByOrderByIdAsc()
                .stream()
                .map(t -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id",   t.getId());
                    m.put("name", t.getName());
                    return m;
                })
                .collect(Collectors.toList());
    }

    // ── Event Reminders ───────────────────────────────────────────────────

    /**
     * Event reminders for the Event Reminders list.
     *
     * <p>Events are soft-deleted ({@code delete_flag}); their reminder rows are not
     * touched when that happens. A reminder whose event is deleted (or not this
     * church's) is therefore left out here — it used to be listed as "Event #18",
     * looking live. The rows themselves stay as they are, untouched, so nothing is
     * lost and nothing about a live event's reminder changes. The scheduler applies
     * the same rule independently ({@code ReminderSchedulerService.runEventReminderRule}
     * only loads non-deleted events), so such a reminder never sends.
     * A reminder with no event (applies to every event) is still listed.
     */
    public List<Map<String, Object>> getAllEventReminders(String appClientId) {
        java.util.Set<Integer> liveEventIds = churchEventRepo
                .findByAppClientIdAndDeleteFlagFalseOrderByCreatedDateDesc(appClientId)
                .stream().map(ChurchEvent::getId).collect(Collectors.toSet());
        return eventRepo.findByAppClientIdOrderByCreatedDateDesc(appClientId)
                .stream()
                .filter(r -> r.getEventId() == null || liveEventIds.contains(r.getEventId()))
                .map(this::eventToMap).collect(Collectors.toList());
    }

    public Map<String, Object> createEventReminder(EventReminderBO bo, String appClientId) {
        EventReminder r = new EventReminder();
        applyEventBO(r, bo, appClientId);
        r.setAppClientId(appClientId);
        return eventToMap(eventRepo.save(r));
    }

    public Map<String, Object> updateEventReminder(Integer id, EventReminderBO bo, String appClientId) {
        EventReminder r = eventRepo.findByIdAndAppClientId(id, appClientId)
                .orElseThrow(() -> new IllegalArgumentException("Event reminder not found"));
        applyEventBO(r, bo, appClientId);
        return eventToMap(eventRepo.save(r));
    }

    public void deleteEventReminder(Integer id, String appClientId) {
        EventReminder r = eventRepo.findByIdAndAppClientId(id, appClientId)
                .orElseThrow(() -> new IllegalArgumentException("Event reminder not found"));
        eventRepo.delete(r);
    }

    private void applyEventBO(EventReminder r, EventReminderBO bo, String appClientId) {
        // The referenced event must belong to this church before it is stored.
        if (bo.getEventId() != null) {
            churchEventRepo.findByIdAndAppClientIdAndDeleteFlagFalse(bo.getEventId(), appClientId)
                    .orElseThrow(() -> new IllegalArgumentException("Event not found"));
        }
        r.setEventId(bo.getEventId());
        r.setSameDay(bo.getSameDay() != null ? bo.getSameDay() : false);
        r.setBeforeDays(bo.getBeforeDays());
        r.setAfterDays(bo.getAfterDays());
        r.setSameDayTemplate(bo.getSameDayTemplate());
        r.setBeforeDaysTemplate(bo.getBeforeDaysTemplate());
        r.setAfterDaysTemplate(bo.getAfterDaysTemplate());
        r.setRecipients(bo.getRecipients());
        if (bo.getDisabled() != null) r.setDisabled(bo.getDisabled());
        r.setNote(bo.getNote());
        r.setSendEmail(bo.getSendEmail()    != null ? bo.getSendEmail()    : true);
        r.setSendSms(bo.getSendSms()        != null ? bo.getSendSms()      : false);
        r.setSendWhatsApp(bo.getSendWhatsApp() != null ? bo.getSendWhatsApp() : true);
    }

    private Map<String, Object> eventToMap(EventReminder r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id",          r.getId());
        m.put("eventId",     r.getEventId());
        // Resolve event name
        String eventName = "";
        if (r.getEventId() != null) {
            eventName = churchEventRepo.findByIdAndAppClientIdAndDeleteFlagFalse(r.getEventId(), r.getAppClientId())
                    .map(ChurchEvent::getEventName).orElse("");
        }
        m.put("eventName",   eventName);
        m.put("sameDay",     r.getSameDay());
        m.put("beforeDays",  r.getBeforeDays());
        m.put("afterDays",   r.getAfterDays());
        m.put("sameDayTemplate",     r.getSameDayTemplate());
        m.put("beforeDaysTemplate",  r.getBeforeDaysTemplate());
        m.put("afterDaysTemplate",   r.getAfterDaysTemplate());
        m.put("recipients",   r.getRecipients());
        m.put("disabled",     r.getDisabled());
        m.put("note",         r.getNote());
        m.put("sendEmail",    r.getSendEmail()    != null ? r.getSendEmail()    : true);
        m.put("sendSms",      r.getSendSms()      != null ? r.getSendSms()      : false);
        m.put("sendWhatsApp", r.getSendWhatsApp() != null ? r.getSendWhatsApp() : true);
        m.put("appClientId",  r.getAppClientId());
        return m;
    }

    // ── Auto Reminders ────────────────────────────────────────────────────

    public List<Map<String, Object>> getAllAutoReminders(String appClientId) {
        return autoRepo.findByAppClientIdOrderByCreatedDateDesc(appClientId)
                .stream().map(this::autoToMap).collect(Collectors.toList());
    }

    public Map<String, Object> createAutoReminder(AutoReminderBO bo, String appClientId) {
        AutoReminder r = new AutoReminder();
        applyAutoBO(r, bo);
        r.setAppClientId(appClientId);
        return autoToMap(autoRepo.save(r));
    }

    public Map<String, Object> updateAutoReminder(Integer id, AutoReminderBO bo, String appClientId) {
        AutoReminder r = autoRepo.findByIdAndAppClientId(id, appClientId)
                .orElseThrow(() -> new IllegalArgumentException("Auto reminder not found"));
        applyAutoBO(r, bo);
        return autoToMap(autoRepo.save(r));
    }

    public void deleteAutoReminder(Integer id, String appClientId) {
        AutoReminder r = autoRepo.findByIdAndAppClientId(id, appClientId)
                .orElseThrow(() -> new IllegalArgumentException("Auto reminder not found"));
        autoRepo.delete(r);
    }

    private void applyAutoBO(AutoReminder r, AutoReminderBO bo) {
        r.setReminderTypeId(bo.getReminderTypeId());
        r.setName(bo.getName());
        r.setRecipients(bo.getRecipients());
        r.setImageData(bo.getImageData());
        if (bo.getDisabled() != null) r.setDisabled(bo.getDisabled());
        r.setNote(bo.getNote());
        r.setEmailTemplate(bo.getEmailTemplate());
        r.setSmsTemplate(bo.getSmsTemplate());
        r.setSendEmail(bo.getSendEmail()       != null ? bo.getSendEmail()       : true);
        r.setSendSms(bo.getSendSms()           != null ? bo.getSendSms()         : false);
        r.setSendWhatsApp(bo.getSendWhatsApp() != null ? bo.getSendWhatsApp()    : true);
    }

    private Map<String, Object> autoToMap(AutoReminder r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id",             r.getId());
        m.put("reminderTypeId", r.getReminderTypeId());
        m.put("name",           r.getName());
        m.put("recipients",     r.getRecipients());
        m.put("imageData",      r.getImageData());
        m.put("disabled",       r.getDisabled());
        m.put("note",           r.getNote());
        m.put("emailTemplate",  r.getEmailTemplate());
        m.put("smsTemplate",    r.getSmsTemplate());
        m.put("sendEmail",      r.getSendEmail()    != null ? r.getSendEmail()    : true);
        m.put("sendSms",        r.getSendSms()      != null ? r.getSendSms()      : false);
        m.put("sendWhatsApp",   r.getSendWhatsApp() != null ? r.getSendWhatsApp() : true);
        m.put("appClientId",    r.getAppClientId());
        return m;
    }

    // ── One-Time Reminders ────────────────────────────────────────────────

    public List<Map<String, Object>> getAllOneTimeReminders(String appClientId) {
        return oneTimeRepo.findByAppClientIdOrderByCreatedDateDesc(appClientId)
                .stream().map(this::oneTimeToMap).collect(Collectors.toList());
    }

    public Map<String, Object> createOneTimeReminder(OneTimeReminderBO bo, String appClientId) {
        OneTimeReminder r = new OneTimeReminder();
        applyOneTimeBO(r, bo);
        r.setAppClientId(appClientId);
        return oneTimeToMap(oneTimeRepo.save(r));
    }

    public Map<String, Object> updateOneTimeReminder(Integer id, OneTimeReminderBO bo, String appClientId) {
        OneTimeReminder r = oneTimeRepo.findByIdAndAppClientId(id, appClientId)
                .orElseThrow(() -> new IllegalArgumentException("One-time reminder not found"));
        applyOneTimeBO(r, bo);
        return oneTimeToMap(oneTimeRepo.save(r));
    }

    public void deleteOneTimeReminder(Integer id, String appClientId) {
        OneTimeReminder r = oneTimeRepo.findByIdAndAppClientId(id, appClientId)
                .orElseThrow(() -> new IllegalArgumentException("One-time reminder not found"));
        oneTimeRepo.delete(r);
    }

    private void applyOneTimeBO(OneTimeReminder r, OneTimeReminderBO bo) {
        r.setName(bo.getName());
        if (bo.getEventDate() != null && !bo.getEventDate().isBlank()) {
            r.setEventDate(LocalDate.parse(bo.getEventDate()));
        }
        r.setRecipients(bo.getRecipients());
        r.setImageData(bo.getImageData());
        if (bo.getDisabled() != null) r.setDisabled(bo.getDisabled());
        r.setNote(bo.getNote());
        r.setSendEmail(bo.getSendEmail()       != null ? bo.getSendEmail()       : true);
        r.setSendSms(bo.getSendSms()           != null ? bo.getSendSms()         : false);
        r.setSendWhatsApp(bo.getSendWhatsApp() != null ? bo.getSendWhatsApp()    : true);
    }

    private Map<String, Object> oneTimeToMap(OneTimeReminder r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id",           r.getId());
        m.put("name",         r.getName());
        m.put("eventDate",    r.getEventDate() != null ? r.getEventDate().toString() : null);
        m.put("recipients",   r.getRecipients());
        m.put("imageData",    r.getImageData());
        m.put("disabled",     r.getDisabled());
        m.put("note",         r.getNote());
        m.put("sendEmail",    r.getSendEmail()    != null ? r.getSendEmail()    : true);
        m.put("sendSms",      r.getSendSms()      != null ? r.getSendSms()      : false);
        m.put("sendWhatsApp", r.getSendWhatsApp() != null ? r.getSendWhatsApp() : true);
        m.put("appClientId",  r.getAppClientId());
        return m;
    }

    // ── Meeting Reminders ─────────────────────────────────────────────────

    public List<Map<String, Object>> getAllMeetingReminders(String appClientId) {
        return meetingRepo.findByAppClientIdOrderByCreatedDateDesc(appClientId)
                .stream().map(this::meetingReminderToMap).collect(Collectors.toList());
    }

    public Map<String, Object> createMeetingReminder(MeetingReminderBO bo, String appClientId) {
        MeetingReminder r = new MeetingReminder();
        applyMeetingBO(r, bo, appClientId);
        r.setAppClientId(appClientId);
        return meetingReminderToMap(meetingRepo.save(r));
    }

    public Map<String, Object> updateMeetingReminder(Integer id, MeetingReminderBO bo, String appClientId) {
        MeetingReminder r = meetingRepo.findByIdAndAppClientId(id, appClientId)
                .orElseThrow(() -> new IllegalArgumentException("Meeting reminder not found"));
        applyMeetingBO(r, bo, appClientId);
        return meetingReminderToMap(meetingRepo.save(r));
    }

    public void deleteMeetingReminder(Integer id, String appClientId) {
        MeetingReminder r = meetingRepo.findByIdAndAppClientId(id, appClientId)
                .orElseThrow(() -> new IllegalArgumentException("Meeting reminder not found"));
        meetingRepo.delete(r);
    }

    private void applyMeetingBO(MeetingReminder r, MeetingReminderBO bo, String appClientId) {
        // The referenced meeting must belong to this church before it is stored.
        if (bo.getMeetingId() != null) {
            meetingRepository.findByIdAndAppClientIdAndDeleteFlagFalse(bo.getMeetingId(), appClientId)
                    .orElseThrow(() -> new IllegalArgumentException("Meeting not found"));
        }
        r.setMeetingId(bo.getMeetingId());
        r.setRecipients(bo.getRecipients());
        if (bo.getDisabled() != null) r.setDisabled(bo.getDisabled());
        r.setNote(bo.getNote());
    }

    private Map<String, Object> meetingReminderToMap(MeetingReminder r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id",          r.getId());
        m.put("meetingId",   r.getMeetingId());
        // Resolve meeting label
        String meetingLabel = "";
        String meetingDate  = "";
        if (r.getMeetingId() != null) {
            // findActiveById fetch-joins meetingType (needed outside a transaction);
            // the tenant filter keeps a foreign meeting's label from leaking.
            meetingRepository.findActiveById(r.getMeetingId())
                    .filter(meeting -> r.getAppClientId() != null
                            && r.getAppClientId().equals(meeting.getAppClientId()))
                    .ifPresent(meeting -> {
                m.put("meetingLabel", meeting.getMeetingType() != null
                        ? meeting.getMeetingType().getTypeName() : "");
                m.put("meetingDate",  meeting.getMeetingDate() != null
                        ? meeting.getMeetingDate().toString() : "");
            });
        }
        if (!m.containsKey("meetingLabel")) m.put("meetingLabel", meetingLabel);
        if (!m.containsKey("meetingDate"))  m.put("meetingDate",  meetingDate);
        m.put("recipients",  r.getRecipients());
        m.put("disabled",    r.getDisabled());
        m.put("note",        r.getNote());
        m.put("appClientId", r.getAppClientId());
        return m;
    }
}
