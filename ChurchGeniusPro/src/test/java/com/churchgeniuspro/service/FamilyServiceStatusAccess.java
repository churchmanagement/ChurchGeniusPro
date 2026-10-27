package com.churchgeniuspro.service;

/** Test-only bridge to FamilyService's package-private status helpers. */
public final class FamilyServiceStatusAccess {
    private FamilyServiceStatusAccess() {}
    public static String status(boolean md, boolean fd, boolean mi, boolean fi) { return FamilyService.memberStatus(md, fd, mi, fi); }
    public static String reason(boolean md, boolean fd, boolean mi, boolean fi) { return FamilyService.memberStatusReason(md, fd, mi, fi); }
}
