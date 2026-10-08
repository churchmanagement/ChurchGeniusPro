package com.churchgeniuspro.sundayschool;

import com.churchgeniuspro.controller.SundaySchoolController;
import com.churchgeniuspro.hibernate.*;
import com.churchgeniuspro.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpSession;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * Sunday School rows carry a clientId, but most handlers used to look children up
 * by a parent id (classId, examId, submissionId) or by a bare findById, so any
 * signed-in session of one church could read another church's rosters, answer
 * keys and marks, or write into them. The member side had the same gap one level
 * down: a student could start any church's exam and save into any submission.
 *
 * <p>These tests pin the tenant boundary for a representative handler of each
 * group. Ids are global, so "another church's row" is simply a row whose
 * clientId is not the session's — the scoped finders return empty for it and
 * the handler must answer 404 without touching it.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SundaySchoolTenantIsolationTest {

    private static final String OURS   = "CHURCH-A";
    private static final String THEIRS = "CHURCH-B";
    private static final String KID_REF = "MBR-kid";
    private static final String SAM_REF = "MBR-sam";

    @Mock SsClassRepository      classRepo;
    @Mock SsTeacherRepository    teacherRepo;
    @Mock SsStudentRepository    studentRepo;
    @Mock SsLessonRepository     lessonRepo;
    @Mock SsExamRepository       examRepo;
    @Mock SsQuestionRepository   questionRepo;
    @Mock SsSubmissionRepository submissionRepo;
    @Mock SsAnswerRepository     answerRepo;
    @Mock SsNoteRepository       noteRepo;
    @Mock SsFileRepository       fileRepo;
    @Mock FamilyMemberRepository familyMemberRepo;
    @Mock com.churchgeniuspro.service.EmailService emailService;

    @InjectMocks SundaySchoolController controller;

    // ── sessions ────────────────────────────────────────────────────────────

    private static MockHttpServletRequest staff(String role) {
        MockHttpServletRequest req = new MockHttpServletRequest();
        MockHttpSession s = new MockHttpSession();
        s.setAttribute("username", "staff@" + OURS);
        s.setAttribute("role", role);
        s.setAttribute("clientId", "USR-1");
        s.setAttribute("appClientId", OURS);
        req.setSession(s);
        return req;
    }

    private static MockHttpServletRequest admin() { return staff("Admin"); }

    /** A member portal login of our church, as the login flow populates it. */
    private static MockHttpServletRequest member(String memberRef, int memberId) {
        MockHttpServletRequest req = new MockHttpServletRequest();
        MockHttpSession s = new MockHttpSession();
        s.setAttribute("username", memberRef + "@mail");
        s.setAttribute("role", "Member");
        s.setAttribute("memberId", memberId);
        s.setAttribute("clientId", memberRef);
        s.setAttribute("appClientId", OURS);
        req.setSession(s);
        return req;
    }

    // ── fixtures ────────────────────────────────────────────────────────────

    private SsClass ourClass(long id) {
        SsClass c = new SsClass();
        c.setId(id); c.setClientId(OURS); c.setClassName("Class " + id);
        when(classRepo.findByIdAndClientIdAndDeleteFlagFalse(id, OURS)).thenReturn(Optional.of(c));
        when(classRepo.findById(id)).thenReturn(Optional.of(c));
        return c;
    }

    private SsExam exam(long id, long classId, String clientId) {
        SsExam e = new SsExam();
        e.setId(id); e.setClassId(classId); e.setClientId(clientId); e.setStatus("Published");
        e.setExamTitle("Exam " + id);
        when(examRepo.findById(id)).thenReturn(Optional.of(e));
        if (OURS.equals(clientId)) {
            when(examRepo.findByIdAndClientIdAndDeleteFlagFalse(id, OURS)).thenReturn(Optional.of(e));
        }
        return e;
    }

    private SsSubmission submission(long id, long examId, long studentId, String clientId) {
        SsSubmission s = new SsSubmission();
        s.setId(id); s.setExamId(examId); s.setStudentId(studentId); s.setClientId(clientId);
        s.setStatus("InProgress");
        when(submissionRepo.findById(id)).thenReturn(Optional.of(s));
        if (OURS.equals(clientId)) {
            when(submissionRepo.findByIdAndClientIdAndDeleteFlagFalse(id, OURS)).thenReturn(Optional.of(s));
        }
        return s;
    }

    private SsAnswer answer(long id, long submissionId, String clientId) {
        SsAnswer a = new SsAnswer();
        a.setId(id); a.setSubmissionId(submissionId); a.setQuestionId(1L); a.setClientId(clientId);
        when(answerRepo.findById(id)).thenReturn(Optional.of(a));
        if (OURS.equals(clientId)) {
            when(answerRepo.findByIdAndClientId(id, OURS)).thenReturn(Optional.of(a));
        }
        return a;
    }

    private static int status(ResponseEntity<?> r) { return r.getStatusCode().value(); }

    // ═══════════════════════════════════════════════════════════════════════
    @Nested
    @DisplayName("Staff side: /api/ss/*")
    class StaffSide {

        private static final long OUR_CLASS = 10L;
        private static final long THEIR_CLASS = 20L;   // no (20, OURS) row in classRepo

        @BeforeEach
        void ourClassExists() { ourClass(OUR_CLASS); }

        @Test
        @DisplayName("Listing another church's class roster is 404 and never queries the roster")
        void foreignClassRosterIsNotFound() {
            assertThat(status(controller.getTeachers(THEIR_CLASS, admin()))).isEqualTo(404);
            assertThat(status(controller.getStudents(THEIR_CLASS, admin()))).isEqualTo(404);
            assertThat(status(controller.getExams(THEIR_CLASS, admin()))).isEqualTo(404);
            verify(teacherRepo, never()).findByClassIdAndDeleteFlagFalse(anyLong());
            verify(studentRepo, never()).findByClassIdAndDeleteFlagFalse(anyLong());
        }

        @Test
        @DisplayName("Adding to another church's class is 404 and nothing is saved")
        void cannotWriteIntoForeignClass() {
            Map<String,String> teacher = Map.of("teacherName", "Sam", "email", "sam@x");
            assertThat(status(controller.addTeacher(THEIR_CLASS, teacher, admin()))).isEqualTo(404);
            assertThat(status(controller.addNote(THEIR_CLASS, Map.of("noteText", "hi"), admin()))).isEqualTo(404);
            Map<String,Object> student = Map.of("teacherId", "1", "studentName", "Kid");
            assertThat(status(controller.addStudent(THEIR_CLASS, student, admin()))).isEqualTo(404);
            verify(teacherRepo, never()).save(any(SsTeacher.class));
            verify(noteRepo, never()).save(any(SsNote.class));
            verify(studentRepo, never()).save(any(SsStudent.class));
        }

        @Test
        @DisplayName("A student cannot be attached to another church's teacher or family member")
        void bodyForeignKeysMustBeOurs() {
            // teacher 7 is not in our tenant; teacher 8 is
            SsTeacher ours = new SsTeacher(); ours.setId(8L); ours.setClientId(OURS); ours.setClassId(OUR_CLASS);
            when(teacherRepo.findByIdAndClientIdAndDeleteFlagFalse(8L, OURS)).thenReturn(Optional.of(ours));

            assertThat(status(controller.addStudent(OUR_CLASS,
                    Map.of("teacherId", "7", "studentName", "Kid"), admin()))).isEqualTo(400);
            assertThat(status(controller.addStudent(OUR_CLASS,
                    Map.of("teacherId", "8", "studentName", "Kid", "familyMemberId", "4242"), admin()))).isEqualTo(400);
            verify(studentRepo, never()).save(any(SsStudent.class));
        }

        @Test
        @DisplayName("Another church's answer key is 404")
        void foreignAnswerKeyIsNotFound() {
            exam(55L, THEIR_CLASS, THEIRS);
            assertThat(status(controller.getQuestions(55L, admin()))).isEqualTo(404);
            verify(questionRepo, never()).findByExamIdAndDeleteFlagFalseOrderBySortOrderAsc(anyLong());
            assertThat(status(controller.getSubmissions(55L, admin()))).isEqualTo(404);
            assertThat(status(controller.addQuestion(55L, Map.of("questionText", "q"), admin()))).isEqualTo(404);
            verify(questionRepo, never()).save(any(SsQuestion.class));
        }

        @Test
        @DisplayName("Marks on another church's answers and submissions cannot be changed")
        void foreignMarksAreNotFound() {
            answer(500L, 900L, THEIRS);
            submission(900L, 55L, 2L, THEIRS);
            assertThat(status(controller.setManualMarks(500L, Map.of("manualMarks", "5"), admin()))).isEqualTo(404);
            assertThat(status(controller.setSubmissionTotalMarks(900L, Map.of("totalMarks", "50"), admin()))).isEqualTo(404);
            assertThat(status(controller.adminExtendTime(900L, Map.of("extraMinutes", "10"), admin()))).isEqualTo(404);
            assertThat(status(controller.getAnswers(900L, admin()))).isEqualTo(404);
            assertThat(status(controller.adminSendResult(900L, null, admin()))).isEqualTo(404);
            verify(answerRepo, never()).save(any(SsAnswer.class));
            verify(submissionRepo, never()).save(any(SsSubmission.class));
            verifyNoInteractions(emailService);
        }

        @Test
        @DisplayName("The staff API carries the /sundaySchool page guard, not just a clientId")
        void roleGateMirrorsPageRoute() {
            assertThat(status(controller.getClasses(staff("Accountant")))).isEqualTo(403);
            assertThat(status(controller.getTeachers(OUR_CLASS, staff("Accountant")))).isEqualTo(403);
            // a Member-role session without a member id is not a member portal login either
            assertThat(status(controller.getClasses(staff("Member")))).isEqualTo(403);
            assertThat(status(controller.getClasses(new MockHttpServletRequest()))).isEqualTo(401);
            verify(classRepo, never()).findByClientIdAndDeleteFlagFalse(anyString());
            // the Admin who owns the page still gets through
            assertThat(status(controller.getClasses(admin()))).isEqualTo(200);
        }

        @Test
        @DisplayName("Same-church staff still manage their own class")
        void ownClassStillWorks() {
            SsTeacher ours = new SsTeacher(); ours.setId(8L); ours.setClientId(OURS); ours.setClassId(OUR_CLASS);
            when(teacherRepo.findByIdAndClientIdAndDeleteFlagFalse(8L, OURS)).thenReturn(Optional.of(ours));
            when(studentRepo.save(any(SsStudent.class))).thenAnswer(i -> i.getArgument(0));

            assertThat(status(controller.getTeachers(OUR_CLASS, admin()))).isEqualTo(200);
            assertThat(status(controller.addStudent(OUR_CLASS,
                    Map.of("teacherId", "8", "studentName", "Kid"), admin()))).isEqualTo(200);
            ArgumentCaptor<SsStudent> cap = ArgumentCaptor.forClass(SsStudent.class);
            verify(studentRepo).save(cap.capture());
            assertThat(cap.getValue().getClientId()).isEqualTo(OURS);
            assertThat(cap.getValue().getClassId()).isEqualTo(OUR_CLASS);
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    @Nested
    @DisplayName("Member side: /api/member/ss/*")
    class MemberSide {

        private static final long KID_CLASS = 10L;
        private static final long OTHER_CLASS = 11L;
        private SsStudent kid;

        @BeforeEach
        void kidIsEnrolled() {
            ourClass(KID_CLASS);
            kid = new SsStudent();
            kid.setId(2L); kid.setClientId(OURS); kid.setClassId(KID_CLASS); kid.setMemberRef(KID_REF);
            kid.setTeacherId(8L);
            when(studentRepo.findByMemberRefAndClientIdAndDeleteFlagFalse(KID_REF, OURS))
                    .thenReturn(Optional.of(kid));
        }

        @Test
        @DisplayName("A student cannot start another church's exam")
        void cannotStartForeignExam() {
            exam(55L, KID_CLASS, THEIRS);
            assertThat(status(controller.startExam(55L, member(KID_REF, 900)))).isEqualTo(404);
            verify(submissionRepo, never()).save(any(SsSubmission.class));
            verify(answerRepo, never()).deleteByExamIdAndStudentId(anyLong(), anyLong());
        }

        @Test
        @DisplayName("A student cannot start an exam of a class they are not enrolled in")
        void cannotStartOtherClassExam() {
            exam(56L, OTHER_CLASS, OURS);
            assertThat(status(controller.startExam(56L, member(KID_REF, 900)))).isEqualTo(404);
            verify(submissionRepo, never()).save(any(SsSubmission.class));
        }

        @Test
        @DisplayName("A student can start their own class's exam")
        void startsOwnExam() {
            exam(57L, KID_CLASS, OURS);
            when(submissionRepo.save(any(SsSubmission.class))).thenAnswer(i -> {
                SsSubmission s = i.getArgument(0); s.setId(700L); return s; });
            assertThat(status(controller.startExam(57L, member(KID_REF, 900)))).isEqualTo(200);
            ArgumentCaptor<SsSubmission> cap = ArgumentCaptor.forClass(SsSubmission.class);
            verify(submissionRepo).save(cap.capture());
            assertThat(cap.getValue().getStudentId()).isEqualTo(2L);
            assertThat(cap.getValue().getClientId()).isEqualTo(OURS);
        }

        @Test
        @DisplayName("Answers can only be saved into, and submitted from, the student's own submission")
        void submissionMustBeOwn() {
            submission(900L, 55L, 2L, THEIRS);     // another church's, even with our student id
            submission(901L, 57L, 3L, OURS);       // a classmate's
            Map<String,Object> body = Map.of("questionId", "1", "answerText", "x");
            assertThat(status(controller.saveAnswer(900L, body, member(KID_REF, 900)))).isEqualTo(404);
            assertThat(status(controller.saveAnswer(901L, body, member(KID_REF, 900)))).isEqualTo(403);
            assertThat(status(controller.submitExam(900L, member(KID_REF, 900)))).isEqualTo(404);
            assertThat(status(controller.submitExam(901L, member(KID_REF, 900)))).isEqualTo(403);
            assertThat(status(controller.pollExtraTime(900L, member(KID_REF, 900)))).isEqualTo(404);
            verify(answerRepo, never()).save(any(SsAnswer.class));
            verify(submissionRepo, never()).save(any(SsSubmission.class));

            submission(902L, 57L, 2L, OURS);       // the student's own
            when(answerRepo.save(any(SsAnswer.class))).thenAnswer(i -> i.getArgument(0));
            assertThat(status(controller.saveAnswer(902L, body, member(KID_REF, 900)))).isEqualTo(200);
            verify(answerRepo).save(any(SsAnswer.class));
        }

        @Test
        @DisplayName("Family views query the parent's own church only, never the whole table")
        void familyViewsAreTenantScoped() {
            FamilyMember self = new FamilyMember();
            self.setId(900); self.setEmail("parent@mail");
            when(familyMemberRepo.findByIdAndTenant(900, OURS)).thenReturn(Optional.of(self));
            when(studentRepo.findByFamilyMemberIdInAndClientIdAndDeleteFlagFalse(List.of(900), OURS))
                    .thenReturn(List.of(kid));

            ResponseEntity<?> r = controller.familyEnrolled(member(KID_REF, 900));
            assertThat(status(r)).isEqualTo(200);
            assertThat((List<?>) r.getBody()).hasSize(1);
            verify(studentRepo, never()).findAll();

            // a member session with no resolvable church gets nothing rather than everything
            MockHttpServletRequest noTenant = member(KID_REF, 900);
            noTenant.getSession().removeAttribute("appClientId");
            assertThat(status(controller.familyEnrolled(noTenant))).isEqualTo(401);
            assertThat(status(controller.familyResults(noTenant))).isEqualTo(401);
            verify(studentRepo, never()).findAll();
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    @Nested
    @DisplayName("Teacher side: /api/member/ss/teacher/*")
    class TeacherSide {

        private static final long SAM_CLASS = 10L;

        private SsTeacher samIn(String clientId) {
            SsTeacher t = new SsTeacher();
            t.setId(1L); t.setClientId(clientId); t.setClassId(SAM_CLASS); t.setMemberRef(SAM_REF);
            t.setEmail(SAM_REF + "@mail");
            return t;
        }

        @Test
        @DisplayName("A teacher record in another church does not make the member a teacher here")
        void teacherResolutionStaysInTenant() {
            // Sam teaches class 10 — but in CHURCH-B. Nothing in CHURCH-A says so.
            // The old unscoped fallbacks (by memberRef alone, by login email alone) would
            // have found the CHURCH-B row and then written our memberRef onto it.
            assertThat(status(controller.teacherGetExams(SAM_CLASS, member(SAM_REF, 501)))).isEqualTo(403);
            assertThat((List<?>) controller.teacherDashboard(member(SAM_REF, 501)).getBody()).isEmpty();
            verify(teacherRepo, never()).save(any(SsTeacher.class));
            verify(examRepo, never()).findByClassIdAndDeleteFlagFalseOrderByIdDesc(anyLong());
        }

        @Test
        @DisplayName("A teacher of our church sees their class, found through the tenant-scoped fallbacks")
        void ownTenantTeacherResolves() {
            ourClass(SAM_CLASS);
            // not linked by memberRef yet — resolved by login email within our church only
            SsTeacher sam = samIn(OURS); sam.setMemberRef(null);
            when(teacherRepo.findByEmailAndClientIdAndDeleteFlagFalse(SAM_REF + "@mail", OURS))
                    .thenReturn(List.of(sam));
            assertThat(status(controller.teacherGetExams(SAM_CLASS, member(SAM_REF, 501)))).isEqualTo(200);
            assertThat((List<?>) controller.teacherDashboard(member(SAM_REF, 501)).getBody()).hasSize(1);
            // the self-heal now only ever stamps a row of our own church
            ArgumentCaptor<SsTeacher> cap = ArgumentCaptor.forClass(SsTeacher.class);
            verify(teacherRepo, atLeastOnce()).save(cap.capture());
            assertThat(cap.getAllValues()).allSatisfy(t -> assertThat(t.getClientId()).isEqualTo(OURS));
        }

        @Test
        @DisplayName("Finalize only touches answers of the submission being finalized")
        void finalizeIsConfinedToSubmission() {
            when(teacherRepo.findByMemberRefAndClientId(SAM_REF, OURS)).thenReturn(List.of(samIn(OURS)));
            exam(57L, SAM_CLASS, OURS);
            submission(902L, 57L, 2L, OURS);
            SsAnswer own   = answer(600L, 902L, OURS);
            SsAnswer other = answer(601L, 903L, OURS);   // some other student's submission
            when(answerRepo.findBySubmissionId(902L)).thenReturn(List.of(own));

            Map<String,Object> body = Map.of("answerMarks", List.of(
                    Map.of("answerId", "600", "manualMarks", "3"),
                    Map.of("answerId", "601", "manualMarks", "9")));
            assertThat(status(controller.teacherFinalizeReview(902L, body, member(SAM_REF, 501)))).isEqualTo(200);

            assertThat(own.getManualMarks()).isEqualTo(3);
            assertThat(other.getManualMarks()).isNull();
            verify(answerRepo).save(own);
            verify(answerRepo, never()).save(other);
        }
    }
}
