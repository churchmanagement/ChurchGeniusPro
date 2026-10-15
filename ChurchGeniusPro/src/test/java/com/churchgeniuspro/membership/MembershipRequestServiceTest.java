package com.churchgeniuspro.membership;

import com.churchgeniuspro.hibernate.Family;
import com.churchgeniuspro.hibernate.FamilyMember;
import com.churchgeniuspro.hibernate.MembershipFamily;
import com.churchgeniuspro.hibernate.MembershipFamilyMember;
import com.churchgeniuspro.hibernate.SignUp;
import com.churchgeniuspro.repository.FamilyMemberRepository;
import com.churchgeniuspro.repository.FamilyRepository;
import com.churchgeniuspro.repository.LoginRepository;
import com.churchgeniuspro.repository.MembershipFamilyMemberRepository;
import com.churchgeniuspro.repository.MembershipFamilyRepository;
import com.churchgeniuspro.service.MembershipRequestService;
import com.churchgeniuspro.util.PhotoImages;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Items 3–6 — matching, diff, atomic apply, renewal date, status transitions, delete. */
@DisplayName("MembershipRequestService — preview, Approve, Approve and Update, status, delete")
class MembershipRequestServiceTest {

    static final String T = "CHURCH-A";
    MembershipFamilyRepository mfRepo; MembershipFamilyMemberRepository mfmRepo;
    FamilyRepository familyRepo; FamilyMemberRepository memberRepo; LoginRepository loginRepo;
    MembershipRequestService svc;
    List<FamilyMember> savedMembers; List<Family> savedFamilies; List<MembershipFamily> savedRequests;

    @BeforeEach
    void setUp() {
        mfRepo = mock(MembershipFamilyRepository.class); mfmRepo = mock(MembershipFamilyMemberRepository.class);
        familyRepo = mock(FamilyRepository.class); memberRepo = mock(FamilyMemberRepository.class); loginRepo = mock(LoginRepository.class);
        savedMembers = new ArrayList<>(); savedFamilies = new ArrayList<>(); savedRequests = new ArrayList<>();
        when(memberRepo.save(any(FamilyMember.class))).thenAnswer(i -> { FamilyMember m = i.getArgument(0); if (m.getId() == null) m.setId(900 + savedMembers.size()); savedMembers.add(m); return m; });
        when(familyRepo.save(any(Family.class))).thenAnswer(i -> { Family f = i.getArgument(0); if (f.getId() == null) f.setId(500); savedFamilies.add(f); return f; });
        when(mfRepo.save(any(MembershipFamily.class))).thenAnswer(i -> { savedRequests.add(i.getArgument(0)); return i.getArgument(0); });
        svc = new MembershipRequestService(mfRepo, mfmRepo, familyRepo, memberRepo, loginRepo, null);
    }

    static Family family(int id, String tenant) { Family f = new Family(); f.setId(id); f.setAppClientId(tenant); return f; }
    static FamilyMember existing(int id, Family f, String role, String first, String last) {
        FamilyMember m = new FamilyMember(); m.setId(id); m.setFamily(f); m.setAppClientId(f.getAppClientId());
        m.setRole(role); m.setFirstName(first); m.setLastName(last); m.setMemberType("Member"); m.setDisableAlerts(true);
        m.setIncludeContributions(true); m.setPhonePrivate(true); m.setPhone("9135550100"); m.setEmail("old@x.org"); m.setCity("Olathe");
        return m;
    }
    static MembershipFamilyMember submitted(int id, String role, String first, String last) {
        MembershipFamilyMember s = new MembershipFamilyMember(); s.setId(id); s.setRole(role); s.setFirstName(first); s.setLastName(last); s.setAppClientId(T);
        s.setSameAsFamilyAddress(true);
        return s;
    }
    MembershipFamily request(int id, Integer existingFamilyId, String status) {
        MembershipFamily mf = new MembershipFamily(); mf.setId(id); mf.setAppClientId(T); mf.setExistingFamilyId(existingFamilyId); mf.setStatus(status);
        when(mfRepo.findByIdAndAppClientIdAndDeleteFlagFalse(id, T)).thenReturn(Optional.of(mf));
        return mf;
    }
    public static String png(int w, int rgb) throws Exception {
        BufferedImage img = new BufferedImage(w, w, BufferedImage.TYPE_INT_RGB);
        for (int x = 0; x < w; x++) for (int y = 0; y < w; y++) img.setRGB(x, y, rgb);
        ByteArrayOutputStream b = new ByteArrayOutputStream(); ImageIO.write(img, "png", b);
        return "data:image/png;base64," + Base64.getEncoder().encodeToString(b.toByteArray());
    }

    @Nested @DisplayName("matching")
    class Matching {
        @Test @DisplayName("name first; Head/Spouse by role when unique; a child is never matched by role alone")
        void rules() {
            Family f = family(7, T);
            FamilyMember head = existing(1, f, "Head", "Anson", "Mathew"), spouse = existing(2, f, "Wife", "Christina", "Mathew");
            FamilyMember kid1 = existing(3, f, "Son", "Asher", "Mathew"), kid2 = existing(4, f, "Child", "Esther", "Mathew");
            List<FamilyMember> ex = List.of(head, spouse, kid1, kid2);
            List<MembershipFamilyMember> sub = List.of(
                    submitted(10, "Head of Household", "ANSON", "mathew"),     // name, case-insensitive
                    submitted(11, "Spouse", "Chris", "Mathew"),                 // renamed → unique Spouse by role
                    submitted(12, "Child", "Esther", "Mathew"),                 // name
                    submitted(13, "Child", "Noah", "Mathew"));                  // new child: NOT matched to Asher
            List<MembershipRequestService.Match> m = MembershipRequestService.match(sub, ex);
            assertThat(m.get(0).existing()).isSameAs(head); assertThat(m.get(0).how()).isEqualTo("NAME");
            assertThat(m.get(1).existing()).isSameAs(spouse); assertThat(m.get(1).how()).isEqualTo("ROLE");
            assertThat(m.get(2).existing()).isSameAs(kid2); assertThat(m.get(2).how()).isEqualTo("NAME");
            assertThat(m.get(3).existing()).isNull(); assertThat(m.get(3).how()).isEqualTo("NEW");
        }

        @Test @DisplayName("two existing heads (Head + Head of Household): role fallback is ambiguous → new member, nobody overwritten")
        void ambiguousRole() {
            Family f = family(7, T);
            List<FamilyMember> ex = List.of(existing(1, f, "Head", "A", "B"), existing(2, f, "Head of Household", "C", "D"));
            List<MembershipRequestService.Match> m = MembershipRequestService.match(List.of(submitted(10, "Head", "X", "Y")), ex);
            assertThat(m.get(0).how()).isEqualTo("NEW");
        }
    }

    @Nested @DisplayName("preview (diff)")
    class Preview {
        @Test @DisplayName("lists only real differences; blank submitted values are not changes; photo change flagged; no-change renewal → hasChanges=false")
        void diff() throws Exception {
            Family f = family(7, T);
            FamilyMember head = existing(1, f, "Head", "Anson", "Mathew"); head.setPhotoData(png(100, 0xFF0000));
            when(familyRepo.findById(7)).thenReturn(Optional.of(f));
            when(memberRepo.findActiveMembersByFamilyId(7)).thenReturn(List.of(head));
            MembershipFamily mf = request(5, 7, "PENDING_REVIEW");

            MembershipFamilyMember s = submitted(10, "Head of Household", "Anson", "Mathew");
            s.setPhone("913-555-0199"); s.setEmail(""); s.setCity("Olathe"); s.setPhotoData(png(100, 0x0000FF));
            Map<String, Object> p = svc.preview(mf, List.of(s), T);
            assertThat(p).containsEntry("isExisting", true).containsEntry("hasChanges", true);
            @SuppressWarnings("unchecked") List<Map<String, Object>> members = (List<Map<String, Object>>) p.get("members");
            @SuppressWarnings("unchecked") List<Map<String, Object>> ch = (List<Map<String, Object>>) members.get(0).get("changes");
            assertThat(ch).extracting(c -> c.get("field")).containsExactly("phone");   // legacy "Head" ≡ "Head of Household"; email blank → not a change; city same
            assertThat(ch.get(0)).containsEntry("oldValue", "9135550100").containsEntry("newValue", "913-555-0199");
            assertThat(members.get(0)).containsEntry("photoChanged", true).containsEntry("matchType", "NAME");

            // same data resubmitted → renewal without changes
            MembershipFamilyMember same = submitted(11, "Head", "Anson", "Mathew"); same.setPhone("9135550100"); same.setEmail("old@x.org"); same.setCity("Olathe");
            Map<String, Object> p2 = svc.preview(mf, List.of(same), T);
            assertThat(p2).containsEntry("hasChanges", false);
        }

        @Test @DisplayName("photo change is reported only for a usable NEW photo: none / blank / unchanged / unsupported (HEIC) are not changes")
        void photoChangeRules() throws Exception {
            Family f = family(7, T);
            String current = png(100, 0xFF0000);
            FamilyMember withPhoto = existing(1, f, "Head", "A", "B"); withPhoto.setPhotoData(current);
            FamilyMember noPhoto = existing(2, f, "Spouse", "C", "B");
            java.util.function.BiFunction<FamilyMember, String, Boolean> changed = (ex, submittedPhoto) -> {
                MembershipFamilyMember s = submitted(10, ex.getRole(), ex.getFirstName(), ex.getLastName()); s.setPhotoData(submittedPhoto);
                return MembershipRequestService.photoChanged(new MembershipRequestService.Match(s, ex, "NAME"));
            };
            String heic = "data:image/heic;base64,AAAAGGZ0eXBoZWljAAAAAG1pZjFoZWljAAAB";
            assertThat(changed.apply(withPhoto, null)).as("no photo submitted").isFalse();
            assertThat(changed.apply(withPhoto, "  ")).as("blank").isFalse();
            assertThat(changed.apply(withPhoto, current)).as("same photo resubmitted").isFalse();
            assertThat(changed.apply(withPhoto, heic)).as("HEIC — browser and server cannot decode it").isFalse();
            assertThat(changed.apply(noPhoto, heic)).as("HEIC onto a member with no photo").isFalse();
            assertThat(changed.apply(noPhoto, null)).as("nothing onto nothing").isFalse();
            assertThat(changed.apply(withPhoto, png(100, 0x0000FF))).as("a different, valid photo").isTrue();
            assertThat(changed.apply(noPhoto, png(100, 0x0000FF))).as("a valid photo where there was none").isTrue();
            // and a NEW member (no existing) with HEIC is not a photo change either
            MembershipFamilyMember n = submitted(11, "Child", "N", "B"); n.setPhotoData(heic);
            assertThat(MembershipRequestService.photoChanged(new MembershipRequestService.Match(n, null, "NEW"))).isFalse();

            // preview end-to-end: HEIC submission for a member with no photo → no change at all
            when(familyRepo.findById(7)).thenReturn(Optional.of(f));
            when(memberRepo.findActiveMembersByFamilyId(7)).thenReturn(List.of(noPhoto));
            MembershipFamilyMember s = submitted(12, "Spouse", "C", "B"); s.setPhotoData(heic); s.setSameAsFamilyAddress(noPhoto.isSameAsFamilyAddress());
            Map<String, Object> p = svc.preview(request(8, 7, "PENDING_REVIEW"), List.of(s), T);
            assertThat(p).containsEntry("hasChanges", false);
            @SuppressWarnings("unchecked") List<Map<String, Object>> members = (List<Map<String, Object>>) p.get("members");
            assertThat(members.get(0)).containsEntry("photoChanged", false).containsEntry("existingPhotoData", null);
        }

        @Test @DisplayName("preview is tenant-scoped: a family of another church is reported missing, never read")
        void crossTenant() {
            when(familyRepo.findById(9)).thenReturn(Optional.of(family(9, "CHURCH-B")));
            Map<String, Object> p = svc.preview(request(6, 9, "PENDING_REVIEW"), List.of(), T);
            assertThat(p).containsEntry("familyMissing", true);
            verify(memberRepo, never()).findActiveMembersByFamilyId(any());
        }
    }

    @Nested @DisplayName("Approve and Update (apply)")
    class Apply {
        @Test @DisplayName("existing family: only differing fields are written, blanks never clear, settings untouched, photo+thumbnail refreshed, new member added, renewal date set, status UPDATED with audit")
        void existingFamily() throws Exception {
            Family f = family(7, T);
            FamilyMember head = existing(1, f, "Head", "Anson", "Mathew"); head.setPhotoData(png(100, 0xFF0000)); head.setPhotoThumbnail(PhotoImages.thumbnail(head.getPhotoData()));
            when(familyRepo.findById(7)).thenReturn(Optional.of(f));
            when(memberRepo.findActiveMembersByFamilyId(7)).thenReturn(List.of(head));
            MembershipFamily mf = request(5, 7, "APPROVED");
            MembershipFamilyMember s = submitted(10, "Head", "Anson", "Mathew");
            s.setPhone("913-555-0199"); s.setEmail(""); s.setPhotoData(png(100, 0x0000FF)); s.setPhonePrivate(false); s.setEmailPrivate(true);
            MembershipFamilyMember kid = submitted(11, "Child", "Noah", "Mathew"); kid.setGender("Male");
            when(mfmRepo.findByMembershipFamily_IdAndDeleteFlagFalse(5)).thenReturn(List.of(s, kid));

            MembershipRequestService.Applied a = svc.apply(5, T, "admin@x.org");

            assertThat(a.isExisting()).isTrue(); assertThat(a.updated()).isEqualTo(1); assertThat(a.created()).isEqualTo(1); assertThat(a.hadChanges()).isTrue();
            assertThat(head.getPhone()).isEqualTo("913-555-0199");
            assertThat(head.getEmail()).as("blank never clears").isEqualTo("old@x.org");
            assertThat(head.getRole()).as("legacy role left as is — not a change").isEqualTo("Head");
            assertThat(head.getMemberType()).isEqualTo("Member");
            assertThat(head.isDisableAlerts()).as("existing settings untouched").isTrue();
            assertThat(head.isIncludeContributions()).isTrue();
            assertThat(head.getPhonePrivate()).as("privacy is never lowered by the form").isTrue();
            assertThat(head.getEmailPrivate()).as("…but can be raised").isTrue();
            assertThat(head.getPhotoData()).isNotEqualTo(png(100, 0xFF0000));
            assertThat(head.getPhotoThumbnail()).isNotNull().isNotEqualTo(PhotoImages.thumbnail(png(100, 0xFF0000)));
            FamilyMember added = savedMembers.stream().filter(m -> "Noah".equals(m.getFirstName())).findFirst().orElseThrow();
            assertThat(added.getFamily()).isSameAs(f); assertThat(added.getRole()).isEqualTo("Child"); assertThat(added.getMemberType()).isEqualTo("Member");
            assertThat(added.isIncludeContributions()).isFalse();
            assertThat(f.getMembershipRenewedAt()).isNotNull();
            assertThat(mf.getStatus()).isEqualTo("UPDATED"); assertThat(mf.getAppliedBy()).isEqualTo("admin@x.org"); assertThat(mf.getAppliedAt()).isNotNull();
            assertThat(mf.getAppliedFamilyId()).isEqualTo(7); assertThat(mf.getStatusChangedBy()).isEqualTo("admin@x.org");
            assertThat(mf.isDeleteFlag()).as("the request stays for reference").isFalse();
        }

        @Test @DisplayName("apply never stores an unsupported (HEIC) photo — existing photo kept, new member gets none")
        void unsupportedPhotoNotStored() throws Exception {
            Family f = family(7, T);
            String current = png(100, 0xFF0000);
            FamilyMember head = existing(1, f, "Head", "A", "B"); head.setPhotoData(current); head.setPhotoThumbnail(PhotoImages.thumbnail(current));
            when(familyRepo.findById(7)).thenReturn(Optional.of(f));
            when(memberRepo.findActiveMembersByFamilyId(7)).thenReturn(List.of(head));
            MembershipFamily mf = request(5, 7, "PENDING_REVIEW");
            String heic = "data:image/heic;base64,AAAAGGZ0eXBoZWljAAAAAG1pZjFoZWljAAAB";
            MembershipFamilyMember s = submitted(10, "Head", "A", "B"); s.setPhone("9135550100"); s.setEmail("old@x.org"); s.setCity("Olathe"); s.setPhotoData(heic);
            MembershipFamilyMember kid = submitted(11, "Child", "New", "B"); kid.setPhotoData(heic);
            when(mfmRepo.findByMembershipFamily_IdAndDeleteFlagFalse(5)).thenReturn(List.of(s, kid));
            MembershipRequestService.Applied a = svc.apply(5, T, "admin");
            assertThat(a.updated()).as("head unchanged — the HEIC is not a change").isEqualTo(0);
            assertThat(a.created()).isEqualTo(1);
            assertThat(head.getPhotoData()).isSameAs(current);
            FamilyMember added = savedMembers.stream().filter(m -> "New".equals(m.getFirstName())).findFirst().orElseThrow();
            assertThat(added.getPhotoData()).isNull(); assertThat(added.getPhotoThumbnail()).isNull();
        }

        @Test @DisplayName("renewal without changes: no member write, renewal date still set, status UPDATED")
        void noChanges() {
            Family f = family(7, T);
            FamilyMember head = existing(1, f, "Head of Household", "Anson", "Mathew");
            when(familyRepo.findById(7)).thenReturn(Optional.of(f));
            when(memberRepo.findActiveMembersByFamilyId(7)).thenReturn(List.of(head));
            MembershipFamily mf = request(5, 7, "PENDING_REVIEW");
            MembershipFamilyMember s = submitted(10, "Head", "Anson", "Mathew"); s.setPhone("9135550100"); s.setEmail("old@x.org"); s.setCity("Olathe");
            when(mfmRepo.findByMembershipFamily_IdAndDeleteFlagFalse(5)).thenReturn(List.of(s));
            MembershipRequestService.Applied a = svc.apply(5, T, "admin");
            assertThat(a.hadChanges()).isFalse();
            verify(memberRepo, never()).save(any());
            assertThat(f.getMembershipRenewedAt()).isNotNull();
            assertThat(mf.getStatus()).isEqualTo("UPDATED");
        }

        @Test @DisplayName("new family: family + members created, signup activated for the head, renewal date set")
        void newFamily() {
            MembershipFamily mf = request(6, null, "PENDING_REVIEW"); mf.setSignupId(33);
            SignUp su = new SignUp(); su.setId(33); su.setClientId("MBR-abc"); su.setActive(false);
            when(loginRepo.findById(33)).thenReturn(Optional.of(su));
            when(loginRepo.save(any(SignUp.class))).thenAnswer(i -> i.getArgument(0));
            when(mfmRepo.findByMembershipFamily_IdAndDeleteFlagFalse(6)).thenReturn(List.of(submitted(1, "Head", "Grace", "Hopper"), submitted(2, "Wife", "Ada", "Hopper")));
            MembershipRequestService.Applied a = svc.apply(6, T, "admin");
            assertThat(a.isExisting()).isFalse(); assertThat(a.created()).isEqualTo(2);
            assertThat(savedFamilies.get(0).getAppClientId()).isEqualTo(T);
            assertThat(savedFamilies.get(0).getMembershipRenewedAt()).isNotNull();
            assertThat(savedMembers).extracting(FamilyMember::getRole).contains("Head of Household", "Spouse");
            assertThat(su.getActive()).isTrue();
            assertThat(savedMembers.stream().filter(m -> "Grace".equals(m.getFirstName())).findFirst().orElseThrow().getMemberRef()).isEqualTo("MBR-abc");
            assertThat(mf.getAppliedFamilyId()).isEqualTo(500);
        }

        @Test @DisplayName("refusals: already UPDATED (409), other church's family (400, nothing written), missing family (400)")
        void refusals() {
            MembershipFamily done = request(1, 7, "UPDATED");
            assertThatThrownBy(() -> svc.apply(1, T, "a")).isInstanceOf(MembershipRequestService.RefusedException.class).hasMessageContaining("already been applied");
            request(2, 8, "PENDING_REVIEW"); when(familyRepo.findById(8)).thenReturn(Optional.of(family(8, "CHURCH-B")));
            when(mfmRepo.findByMembershipFamily_IdAndDeleteFlagFalse(2)).thenReturn(List.of(submitted(1, "Head", "A", "B")));
            assertThatThrownBy(() -> svc.apply(2, T, "a")).hasMessage(MembershipRequestService.ERR_CROSS_TENANT);
            request(3, 9, "PENDING_REVIEW"); when(familyRepo.findById(9)).thenReturn(Optional.empty());
            assertThatThrownBy(() -> svc.apply(3, T, "a")).hasMessage(MembershipRequestService.ERR_FAMILY_MISSING);
            verify(memberRepo, never()).save(any()); verify(familyRepo, never()).save(any());
            assertThat(done.getStatus()).isEqualTo("UPDATED");
        }

        @Test @DisplayName("atomic: a failure mid-way rolls the transaction back and the request keeps its previous status")
        void rollback() {
            AtomicInteger rollbacks = new AtomicInteger();
            PlatformTransactionManager txm = new PlatformTransactionManager() {
                public TransactionStatus getTransaction(TransactionDefinition d) { return new SimpleTransactionStatus(); }
                public void commit(TransactionStatus s) { throw new AssertionError("must not commit"); }
                public void rollback(TransactionStatus s) { rollbacks.incrementAndGet(); }
            };
            MembershipRequestService txSvc = new MembershipRequestService(mfRepo, mfmRepo, familyRepo, memberRepo, loginRepo, txm);
            MembershipFamily mf = request(6, null, "APPROVED");
            when(mfmRepo.findByMembershipFamily_IdAndDeleteFlagFalse(6)).thenReturn(List.of(submitted(1, "Head", "A", "B"), submitted(2, "Child", "C", "B")));
            when(memberRepo.save(any(FamilyMember.class))).thenAnswer(i -> { FamilyMember m = i.getArgument(0); if ("C".equals(m.getFirstName())) throw new IllegalStateException("db down"); return m; });
            assertThatThrownBy(() -> txSvc.apply(6, T, "a")).isInstanceOf(IllegalStateException.class);
            assertThat(rollbacks.get()).isEqualTo(1);
            assertThat(mf.getStatus()).as("status untouched by the failed attempt").isEqualTo("APPROVED");
            assertThat(mf.getAppliedAt()).isNull();
        }
    }

    @Nested @DisplayName("status and delete")
    class Status {
        @Test @DisplayName("Approve is status-only: APPROVED with actor/time; family never touched; renewal date untouched")
        void approve() {
            MembershipFamily mf = request(5, 7, "PENDING_REVIEW");
            MembershipFamily out = svc.approve(5, T, "admin@x.org");
            assertThat(out.getStatus()).isEqualTo("APPROVED"); assertThat(out.getStatusChangedBy()).isEqualTo("admin@x.org"); assertThat(out.getStatusChangedAt()).isNotNull();
            verify(familyRepo, never()).findById(any()); verify(familyRepo, never()).save(any()); verify(memberRepo, never()).save(any());
            assertThat(mf.getAppliedAt()).isNull();
        }

        @Test @DisplayName("transitions: APPROVED→PENDING, UPDATED→APPROVED/PENDING allowed; UPDATED only via apply; same-state and unknown refused")
        void transitions() {
            request(1, null, "APPROVED");
            assertThat(svc.setStatus(1, T, "a", "PENDING_REVIEW").getStatus()).isEqualTo("PENDING_REVIEW");
            request(2, null, "UPDATED");
            assertThat(svc.setStatus(2, T, "a", "APPROVED").getStatus()).isEqualTo("APPROVED");
            request(3, null, "UPDATED");
            assertThat(svc.setStatus(3, T, "a", "PENDING_REVIEW").getStatus()).isEqualTo("PENDING_REVIEW");
            request(4, null, "PENDING_REVIEW");
            assertThatThrownBy(() -> svc.setStatus(4, T, "a", "UPDATED")).isInstanceOf(MembershipRequestService.RefusedException.class);
            assertThatThrownBy(() -> svc.setStatus(4, T, "a", "PENDING_REVIEW")).isInstanceOf(MembershipRequestService.RefusedException.class);
            assertThatThrownBy(() -> svc.setStatus(4, T, "a", "BOGUS")).isInstanceOf(MembershipRequestService.RefusedException.class);
            assertThatThrownBy(() -> svc.setStatus(99, T, "a", "APPROVED")).hasMessage(MembershipRequestService.ERR_NOT_FOUND);
        }

        @Test @DisplayName("delete is a tenant-scoped soft delete with actor/time; family and members untouched")
        void delete() {
            MembershipFamily mf = request(5, 7, "APPROVED");
            svc.delete(5, T, "admin@x.org");
            assertThat(mf.isDeleteFlag()).isTrue(); assertThat(mf.getDeletedBy()).isEqualTo("admin@x.org"); assertThat(mf.getDeletedAt()).isNotNull();
            assertThat(mf.getStatus()).as("status kept for the audit trail").isEqualTo("APPROVED");
            verify(familyRepo, never()).save(any()); verify(memberRepo, never()).save(any()); verify(familyRepo, never()).findById(any());
            assertThatThrownBy(() -> svc.delete(5, "CHURCH-B", "x")).hasMessage(MembershipRequestService.ERR_NOT_FOUND);
        }
    }
}
