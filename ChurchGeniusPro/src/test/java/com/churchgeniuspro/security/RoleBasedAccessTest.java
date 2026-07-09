package com.churchgeniuspro.security;

import com.churchgeniuspro.util.RoleGuard;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Role-based access control tests for the opt-in-denial permission guard that
 * gates every page route and protected API. Verifies the four key paths:
 * unauthenticated, explicit denial, explicit grant, and church bypass.
 */
class RoleBasedAccessTest {

    private HttpServletRequest reqWith(HttpSession session) {
        HttpServletRequest req = mock(HttpServletRequest.class);
        when(req.getSession(false)).thenReturn(session);
        return req;
    }

    private HttpSession staffSession(String role, String privilegesJson) {
        HttpSession s = mock(HttpSession.class);
        when(s.getAttribute("username")).thenReturn("staff-user");
        when(s.getAttribute("role")).thenReturn(role);
        when(s.getAttribute("church")).thenReturn(null);
        when(s.getAttribute("memberId")).thenReturn(null);
        when(s.getAttribute("privileges")).thenReturn(privilegesJson);
        return s;
    }

    @Test
    void noSession_redirectsToLogin() {
        String r = RoleGuard.requirePermission(reqWith(null), "admin.family");
        assertEquals("redirect:/login", r);
    }

    @Test
    void explicitFalse_isDenied() {
        HttpSession s = staffSession("User", "{\"admin.family\":false}");
        assertEquals(RoleGuard.FORWARD_ACCESS_DENIED, RoleGuard.requirePermission(reqWith(s), "admin.family"));
    }

    @Test
    void explicitTrue_isAllowed() {
        HttpSession s = staffSession("User", "{\"admin.family\":true}");
        assertNull(RoleGuard.requirePermission(reqWith(s), "admin.family"));
    }

    @Test
    void missingKey_isAllowed_optInDenial() {
        HttpSession s = staffSession("User", "{\"accounting.income\":false}");
        assertNull(RoleGuard.requirePermission(reqWith(s), "admin.family"));
    }

    @Test
    void nullPrivileges_grantsFullAccess() {
        HttpSession s = staffSession("Admin", null);
        assertNull(RoleGuard.requirePermission(reqWith(s), "admin.family"));
    }

    @Test
    void churchSession_bypassesGranularPermissions() {
        HttpSession s = mock(HttpSession.class);
        when(s.getAttribute("username")).thenReturn("church");
        when(s.getAttribute("church")).thenReturn(Boolean.TRUE);
        assertNull(RoleGuard.requirePermission(reqWith(s), "admin.family"));
    }
}
