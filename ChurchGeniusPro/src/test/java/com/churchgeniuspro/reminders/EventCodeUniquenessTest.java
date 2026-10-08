package com.churchgeniuspro.reminders;

import com.churchgeniuspro.hibernate.ChurchEvent;
import com.churchgeniuspro.model.ChurchEventBO;
import com.churchgeniuspro.repository.ChurchEventRepository;
import com.churchgeniuspro.service.ChurchEventService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * The optional Event ID / Code, and the one rule it has: it must be unique.
 *
 * <p>{@code church_event.event_code} carries a unique index, so a duplicate was
 * always rejected — but only by the database, at save time, as an opaque
 * failure that named no field. Someone who typed a code another event already
 * used saw "Failed to save event" and had no way to know which of the twenty
 * inputs on that form was the problem.
 *
 * <p>The field stays optional throughout: blank is always valid, and these
 * tests pin that as firmly as the uniqueness rule, because a check like this is
 * exactly the kind that quietly turns an optional field into a required one.
 */
class EventCodeUniquenessTest {

    private static final String CLIENT = "CHR-100";

    private ChurchEventRepository eventRepo;
    private ChurchEventService    svc;

    @BeforeEach
    void setUp() throws Exception {
        eventRepo = mock(ChurchEventRepository.class);
        when(eventRepo.save(any(ChurchEvent.class))).thenAnswer(i -> {
            ChurchEvent e = i.getArgument(0);
            if (e.getId() == null) e.setId(7);
            return e;
        });
        when(eventRepo.findFirstByEventCodeIgnoreCaseAndDeleteFlagFalse(anyString()))
                .thenReturn(Optional.empty());
        svc = build();
    }

    /** Instantiate the service with mocks resolved by parameter type. */
    private ChurchEventService build() throws Exception {
        Constructor<?> ctor = ChurchEventService.class.getDeclaredConstructors()[0];
        Object[] args = new Object[ctor.getParameterCount()];
        Class<?>[] types = ctor.getParameterTypes();
        for (int i = 0; i < types.length; i++) {
            args[i] = types[i] == ChurchEventRepository.class ? eventRepo
                    : (types[i].isPrimitive() ? defaultPrimitive(types[i]) : mock(types[i]));
        }
        ctor.setAccessible(true);
        return (ChurchEventService) ctor.newInstance(args);
    }

    private static Object defaultPrimitive(Class<?> t) {
        if (t == boolean.class) return false;
        if (t == int.class)     return 0;
        if (t == long.class)    return 0L;
        return null;
    }

    private ChurchEventBO bo(String code) {
        ChurchEventBO b = new ChurchEventBO();
        b.setEventName("Annual Picnic");
        b.setEventType("One Day");
        b.setEventCode(code);
        return b;
    }

    private ChurchEvent existing(int id, String name, String code) {
        ChurchEvent e = new ChurchEvent();
        e.setId(id);
        e.setEventName(name);
        e.setEventCode(code);
        e.setAppClientId(CLIENT);
        return e;
    }

    // ── optional ───────────────────────────────────────────────────────────

    @Test
    @DisplayName("no code at all is fine — the field is optional")
    void blankCodeIsAccepted() {
        assertDoesNotThrow(() -> svc.create(bo(null), CLIENT, "pastor"));
        assertDoesNotThrow(() -> svc.create(bo(""),   CLIENT, "pastor"));
        assertDoesNotThrow(() -> svc.create(bo("   "), CLIENT, "pastor"));
        verify(eventRepo, never()).findFirstByEventCodeIgnoreCaseAndDeleteFlagFalse(anyString());
    }

    @Test
    @DisplayName("a code nobody else is using is accepted")
    void freeCodeIsAccepted() {
        assertDoesNotThrow(() -> svc.create(bo("EVT-A3B7C2"), CLIENT, "pastor"));
    }

    // ── unique ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("a code another event already holds is refused, naming that event")
    void duplicateCodeIsRefused() {
        when(eventRepo.findFirstByEventCodeIgnoreCaseAndDeleteFlagFalse("EVT-A3B7C2"))
                .thenReturn(Optional.of(existing(3, "Youth Retreat", "EVT-A3B7C2")));

        ChurchEventService.DuplicateEventCodeException e =
                assertThrows(ChurchEventService.DuplicateEventCodeException.class,
                        () -> svc.create(bo("EVT-A3B7C2"), CLIENT, "pastor"));

        assertTrue(e.getMessage().contains("EVT-A3B7C2"), "the message must quote the code");
        assertTrue(e.getMessage().contains("Youth Retreat"),
                "and name the event holding it, so the person can go and look");
        verify(eventRepo, never()).save(any(ChurchEvent.class));
    }

    @Test
    @DisplayName("case and surrounding spaces do not make a code different")
    void comparisonIgnoresCaseAndSpacing() {
        when(eventRepo.findFirstByEventCodeIgnoreCaseAndDeleteFlagFalse("evt-a3b7c2"))
                .thenReturn(Optional.of(existing(3, "Youth Retreat", "EVT-A3B7C2")));

        assertThrows(ChurchEventService.DuplicateEventCodeException.class,
                () -> svc.create(bo("  evt-a3b7c2  "), CLIENT, "pastor"),
                "two codes that differ only in case would make the code useless for searching");
    }

    // ── editing ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("an event keeps its own code when edited")
    void editingKeepsItsOwnCode() {
        ChurchEvent self = existing(7, "Annual Picnic", "EVT-A3B7C2");
        when(eventRepo.findByIdAndAppClientIdAndDeleteFlagFalse(7, CLIENT)).thenReturn(Optional.of(self));
        when(eventRepo.findFirstByEventCodeIgnoreCaseAndDeleteFlagFalse("EVT-A3B7C2"))
                .thenReturn(Optional.of(self));

        assertDoesNotThrow(() -> svc.update(7, bo("EVT-A3B7C2"), "pastor", CLIENT),
                "an event must not be blocked by its own code");
    }

    @Test
    @DisplayName("an edit cannot take a code belonging to a different event")
    void editingCannotStealAnotherCode() {
        ChurchEvent self  = existing(7, "Annual Picnic", "EVT-OWN111");
        ChurchEvent other = existing(3, "Youth Retreat", "EVT-A3B7C2");
        when(eventRepo.findByIdAndAppClientIdAndDeleteFlagFalse(7, CLIENT)).thenReturn(Optional.of(self));
        when(eventRepo.findFirstByEventCodeIgnoreCaseAndDeleteFlagFalse("EVT-A3B7C2"))
                .thenReturn(Optional.of(other));

        assertThrows(ChurchEventService.DuplicateEventCodeException.class,
                () -> svc.update(7, bo("EVT-A3B7C2"), "pastor", CLIENT));
    }

    @Test
    @DisplayName("the refusal is distinguishable, so the page can point at the right field")
    void refusalIsTypedForTheUi() {
        when(eventRepo.findFirstByEventCodeIgnoreCaseAndDeleteFlagFalse("EVT-A3B7C2"))
                .thenReturn(Optional.of(existing(3, "Youth Retreat", "EVT-A3B7C2")));

        Exception e = assertThrows(Exception.class,
                () -> svc.create(bo("EVT-A3B7C2"), CLIENT, "pastor"));

        assertInstanceOf(ChurchEventService.DuplicateEventCodeException.class, e);
        assertInstanceOf(IllegalArgumentException.class, e,
                "it must still be an IllegalArgumentException so existing handlers keep working");
    }
}
