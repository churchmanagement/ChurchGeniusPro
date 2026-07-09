package com.churchgeniuspro.model;

import lombok.Data;

import java.util.List;

/**
 * Business Object representing the Save Family form submission from
 * {@code family.html}.
 *
 * <p>Received as the JSON request body of {@code POST /api/family} and
 * forwarded to {@link com.churchgeniuspro.service.FamilyService} which
 * maps it to the {@link com.churchgeniuspro.hibernate.Family} and
 * {@link com.churchgeniuspro.hibernate.FamilyMember} entities.
 *
 * <p>The family display name and address are no longer stored on the
 * {@code family} row — they are derived from the primary member
 * ({@code role = "Head of Household"}) stored in {@code family_member}.
 */
@Data
public class FamilyBO {

    /**
     * Inactive flag submitted from the Family Details checkbox.
     * {@code true} marks the family as inactive in the {@code family} table.
     */
    private boolean inactive;

    /** One or more members to be saved with this family. */
    private List<FamilyMemberBO> members;
}
