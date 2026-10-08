package com.churchgeniuspro.sundayschool;

import com.churchgeniuspro.controller.SundaySchoolController;
import com.churchgeniuspro.hibernate.*;
import com.churchgeniuspro.repository.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockMultipartFile;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;

/**
 * Sunday School class documents are seen by three audiences, and the point of the
 * feature is that each sees only what belongs to them: Kids Ministry staff see
 * every class, a teacher sees the classes they are assigned to, and a child sees
 * documents shared with the one class they are enrolled in.
 *
 * <p>Before this change the file endpoints authorised on clientId alone, which a
 * Member session also resolves — so any signed-in member of the church could list,
 * upload to, download from and delete any class's files. These tests pin the
 * boundaries that closed that, and the rule that documents uploaded while the
 * section was staff-only are not retroactively exposed to children.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ClassDocumentAccessTest {

    private static final String CLIENT  = "CHR-CHURCH";
    private static final Long   JUNIOR  = 10L;   // the class Sam teaches
    private static final Long   SENIOR  = 20L;   // somebody else's class
    private static final String SAM_REF = "MBR-sam";
    private static final String KID_REF = "MBR-kid";

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

    private HttpServletRequest staff() {
        HttpSession s = mock(HttpSession.class);
        when(s.getAttribute("username")).thenReturn("admin@church.org");
        when(s.getAttribute("role")).thenReturn("Admin");
        when(s.getAttribute("appClientId")).thenReturn(CLIENT);
        return withSession(s);
    }

    /** Sam, a Member who teaches Junior. */
    private HttpServletRequest teacherSam() {
        HttpSession s = mock(HttpSession.class);
        when(s.getAttribute("role")).thenReturn("Member");
        when(s.getAttribute("memberId")).thenReturn(501);
        when(s.getAttribute("clientId")).thenReturn(SAM_REF);
        when(s.getAttribute("appClientId")).thenReturn(CLIENT);
        return withSession(s);
    }

    /** A child enrolled in Junior. */
    private HttpServletRequest studentKid() {
        HttpSession s = mock(HttpSession.class);
        when(s.getAttribute("role")).thenReturn("Member");
        when(s.getAttribute("memberId")).thenReturn(900);
        when(s.getAttribute("clientId")).thenReturn(KID_REF);
        when(s.getAttribute("appClientId")).thenReturn(CLIENT);
        return withSession(s);
    }

    private HttpServletRequest withSession(HttpSession s) {
        HttpServletRequest r = mock(HttpServletRequest.class);
        when(r.getSession(false)).thenReturn(s);
        return r;
    }

    // ── fixtures ────────────────────────────────────────────────────────────

    private SsFile file(long id, Long classId, boolean shared) {
        SsFile f = new SsFile();
        f.setId(id);
        f.setClientId(CLIENT);
        f.setClassId(classId);
        f.setOriginalName("lesson.pdf");
        f.setContentType("application/pdf");
        f.setFileData(java.util.Base64.getEncoder()
                .encodeToString("hello".getBytes(StandardCharsets.UTF_8)));
        f.setSharedWithStudents(shared);
        return f;
    }

    private static MockMultipartFile pdf() {
        return new MockMultipartFile("file", "lesson.pdf", "application/pdf",
                "hello".getBytes(StandardCharsets.UTF_8));
    }

    private static int status(ResponseEntity<?> r) { return r.getStatusCode().value(); }

    @BeforeEach
    void wireRelationships() {
        // Sam teaches Junior, and only Junior.
        SsTeacher sam = new SsTeacher();
        sam.setId(1L); sam.setClientId(CLIENT); sam.setClassId(JUNIOR); sam.setMemberRef(SAM_REF);
        when(teacherRepo.findByMemberRefAndClientId(SAM_REF, CLIENT)).thenReturn(List.of(sam));
        when(teacherRepo.findByMemberRefAndClientIdAndDeleteFlagFalse(SAM_REF, CLIENT)).thenReturn(List.of(sam));

        // Both classes belong to this church; staff manage classes of their own church only.
        for (Long classId : List.of(JUNIOR, SENIOR)) {
            SsClass c = new SsClass();
            c.setId(classId); c.setClientId(CLIENT); c.setClassName("Class " + classId);
            when(classRepo.findByIdAndClientIdAndDeleteFlagFalse(classId, CLIENT)).thenReturn(Optional.of(c));
        }

        // The child is enrolled in Junior, and only Junior.
        SsStudent kid = new SsStudent();
        kid.setId(2L); kid.setClientId(CLIENT); kid.setClassId(JUNIOR); kid.setMemberRef(KID_REF);
        when(studentRepo.findByMemberRefAndClientIdAndDeleteFlagFalse(KID_REF, CLIENT))
                .thenReturn(Optional.of(kid));
        when(studentRepo.findByMemberRefAndDeleteFlagFalse(KID_REF)).thenReturn(List.of(kid));
    }

    // ══ Teachers are confined to their own classes ═════════════════════════

    @Test
    @DisplayName("A teacher can list documents for the class they teach")
    void teacherListsOwnClass() {
        when(fileRepo.findByClassIdAndDeleteFlagFalseOrderByUploadedAtDesc(JUNIOR))
                .thenReturn(List.of(file(1, JUNIOR, true)));
        assertThat(status(controller.getFiles(JUNIOR, teacherSam()))).isEqualTo(200);
    }

    @Test
    @DisplayName("A teacher cannot list another class's documents")
    void teacherCannotListOtherClass() {
        assertThat(status(controller.getFiles(SENIOR, teacherSam()))).isEqualTo(403);
    }

    @Test
    @DisplayName("A teacher cannot upload to another class")
    void teacherCannotUploadToOtherClass() throws Exception {
        assertThat(status(controller.uploadFile(SENIOR, pdf(), true, teacherSam()))).isEqualTo(403);
        verify(fileRepo, never()).save(any(SsFile.class));
    }

    @Test
    @DisplayName("A teacher can upload to their own class, shared with students by default")
    void teacherUploadsToOwnClass() throws Exception {
        when(fileRepo.save(any(SsFile.class))).thenAnswer(i -> i.getArgument(0));
        assertThat(status(controller.uploadFile(JUNIOR, pdf(), true, teacherSam()))).isEqualTo(200);
        ArgumentCaptor<SsFile> cap = ArgumentCaptor.forClass(SsFile.class);
        verify(fileRepo).save(cap.capture());
        assertThat(cap.getValue().getClassId()).isEqualTo(JUNIOR);
        assertThat(cap.getValue().isSharedWithStudents()).isTrue();
    }

    @Test
    @DisplayName("A teacher may upload teacher-only material by unticking the box")
    void teacherMayUploadPrivately() throws Exception {
        when(fileRepo.save(any(SsFile.class))).thenAnswer(i -> i.getArgument(0));
        controller.uploadFile(JUNIOR, pdf(), false, teacherSam());
        ArgumentCaptor<SsFile> cap = ArgumentCaptor.forClass(SsFile.class);
        verify(fileRepo).save(cap.capture());
        assertThat(cap.getValue().isSharedWithStudents()).isFalse();
    }

    @Test
    @DisplayName("A teacher cannot delete another class's document")
    void teacherCannotDeleteOtherClassDocument() {
        when(fileRepo.findById(7L)).thenReturn(Optional.of(file(7, SENIOR, true)));
        assertThat(status(controller.deleteFile(7L, teacherSam()))).isEqualTo(403);
        verify(fileRepo, never()).save(any(SsFile.class));
    }

    @Test
    @DisplayName("A teacher cannot share another class's document with its students")
    void teacherCannotShareOtherClassDocument() {
        when(fileRepo.findById(8L)).thenReturn(Optional.of(file(8, SENIOR, false)));
        assertThat(status(controller.setFileShared(8L, true, teacherSam()))).isEqualTo(403);
        verify(fileRepo, never()).save(any(SsFile.class));
    }

    // ══ Staff keep working exactly as before, with no new privilege ════════

    @Test
    @DisplayName("Kids Ministry staff may manage any class")
    void staffManagesAnyClass() throws Exception {
        when(fileRepo.findByClassIdAndDeleteFlagFalseOrderByUploadedAtDesc(anyLong())).thenReturn(List.of());
        when(fileRepo.save(any(SsFile.class))).thenAnswer(i -> i.getArgument(0));
        assertThat(status(controller.getFiles(SENIOR, staff()))).isEqualTo(200);
        assertThat(status(controller.uploadFile(SENIOR, pdf(), true, staff()))).isEqualTo(200);
    }

    @Test
    @DisplayName("Kids Ministry staff cannot manage a class of another church")
    void staffCannotManageOtherChurchClass() throws Exception {
        long foreign = 99L;   // classRepo has no row for (99, CLIENT)
        assertThat(status(controller.getFiles(foreign, staff()))).isEqualTo(403);
        assertThat(status(controller.uploadFile(foreign, pdf(), true, staff()))).isEqualTo(403);
        verify(fileRepo, never()).save(any(SsFile.class));
    }

    // ══ Students: own class only, shared only, read only ═══════════════════

    @Test
    @DisplayName("A student can download a document shared with their class")
    void studentDownloadsSharedDocument() {
        when(fileRepo.findById(1L)).thenReturn(Optional.of(file(1, JUNIOR, true)));
        assertThat(status(controller.downloadFile(1L, false, studentKid()))).isEqualTo(200);
    }

    @Test
    @DisplayName("A student can open a shared document inline, which is what View and Print use")
    void studentCanViewInline() {
        when(fileRepo.findById(1L)).thenReturn(Optional.of(file(1, JUNIOR, true)));
        ResponseEntity<?> r = controller.downloadFile(1L, true, studentKid());
        assertThat(status(r)).isEqualTo(200);
        assertThat(r.getHeaders().getFirst("Content-Disposition")).startsWith("inline");
    }

    @Test
    @DisplayName("A student cannot download a document that was not shared")
    void studentCannotDownloadUnsharedDocument() {
        when(fileRepo.findById(2L)).thenReturn(Optional.of(file(2, JUNIOR, false)));
        assertThat(status(controller.downloadFile(2L, false, studentKid()))).isEqualTo(403);
    }

    @Test
    @DisplayName("A student cannot download another class's document, even a shared one")
    void studentCannotDownloadOtherClassDocument() {
        when(fileRepo.findById(3L)).thenReturn(Optional.of(file(3, SENIOR, true)));
        assertThat(status(controller.downloadFile(3L, false, studentKid()))).isEqualTo(403);
    }

    @Test
    @DisplayName("A student cannot list, upload to or delete from their class")
    void studentIsReadOnly() throws Exception {
        assertThat(status(controller.getFiles(JUNIOR, studentKid()))).isEqualTo(403);
        assertThat(status(controller.uploadFile(JUNIOR, pdf(), true, studentKid()))).isEqualTo(403);
        when(fileRepo.findById(1L)).thenReturn(Optional.of(file(1, JUNIOR, true)));
        assertThat(status(controller.deleteFile(1L, studentKid()))).isEqualTo(403);
        verify(fileRepo, never()).save(any(SsFile.class));
    }

    // ══ View honours exactly the same boundaries as download ═══════════════

    @Test
    @DisplayName("A student can view a shared document, and it is not sent as a download")
    void studentCanViewSharedDocument() {
        when(fileRepo.findById(1L)).thenReturn(Optional.of(file(1, JUNIOR, true)));
        ResponseEntity<?> r = controller.viewFile(1L, studentKid());
        assertThat(status(r)).isEqualTo(200);
        assertThat(r.getHeaders().getFirst("Content-Disposition"))
                .as("View must never trigger a download")
                .doesNotContain("attachment");
    }

    @Test
    @DisplayName("A student cannot view an unshared or another class's document")
    void studentCannotViewWhatTheyMayNotDownload() {
        when(fileRepo.findById(2L)).thenReturn(Optional.of(file(2, JUNIOR, false)));
        assertThat(status(controller.viewFile(2L, studentKid()))).isEqualTo(403);
        when(fileRepo.findById(3L)).thenReturn(Optional.of(file(3, SENIOR, true)));
        assertThat(status(controller.viewFile(3L, studentKid()))).isEqualTo(403);
    }

    @Test
    @DisplayName("A teacher cannot view another class's document")
    void teacherCannotViewOtherClassDocument() {
        when(fileRepo.findById(4L)).thenReturn(Optional.of(file(4, SENIOR, true)));
        assertThat(status(controller.viewFile(4L, teacherSam()))).isEqualTo(403);
    }

    @Test
    @DisplayName("A Word document is served as a readable HTML page rather than a file")
    void wordIsRenderedForViewing() throws Exception {
        SsFile word = file(5, JUNIOR, true);
        word.setOriginalName("lesson.docx");
        word.setContentType("application/vnd.openxmlformats-officedocument.wordprocessingml.document");
        word.setFileData(java.util.Base64.getEncoder().encodeToString(realDocx("Read Luke 10")));
        when(fileRepo.findById(5L)).thenReturn(Optional.of(word));
        controller.setDocRendererForTest(new com.churchgeniuspro.service.DocumentHtmlRenderer());

        ResponseEntity<?> r = controller.viewFile(5L, studentKid());

        assertThat(status(r)).isEqualTo(200);
        assertThat(r.getHeaders().getFirst("Content-Type")).startsWith("text/html");
        assertThat(r.getHeaders().getFirst("Content-Disposition")).doesNotContain("attachment");
        assertThat(new String((byte[]) r.getBody(), StandardCharsets.UTF_8)).contains("Read Luke 10");
    }

    @Test
    @DisplayName("Download still returns the original Word file, unchanged")
    void downloadStillReturnsTheOriginalFile() throws Exception {
        byte[] original = realDocx("Read Luke 10");
        SsFile word = file(6, JUNIOR, true);
        word.setOriginalName("lesson.docx");
        word.setFileData(java.util.Base64.getEncoder().encodeToString(original));
        when(fileRepo.findById(6L)).thenReturn(Optional.of(word));
        controller.setDocRendererForTest(new com.churchgeniuspro.service.DocumentHtmlRenderer());

        ResponseEntity<?> r = controller.downloadFile(6L, false, studentKid());

        assertThat(status(r)).isEqualTo(200);
        assertThat(r.getHeaders().getFirst("Content-Disposition")).startsWith("attachment");
        assertThat((byte[]) r.getBody()).isEqualTo(original);
    }

    @Test
    @DisplayName("A PDF is still streamed as-is by View — only Word is converted")
    void pdfIsNotConverted() {
        when(fileRepo.findById(1L)).thenReturn(Optional.of(file(1, JUNIOR, true)));   // lesson.pdf
        controller.setDocRendererForTest(new com.churchgeniuspro.service.DocumentHtmlRenderer());
        ResponseEntity<?> r = controller.viewFile(1L, studentKid());
        assertThat(status(r)).isEqualTo(200);
        assertThat(r.getHeaders().getFirst("Content-Type")).isEqualTo("application/pdf");
    }

    @Test
    @DisplayName("A Word file that cannot be parsed falls back to the file, not an error")
    void unreadableWordFallsBack() {
        SsFile broken = file(9, JUNIOR, true);
        broken.setOriginalName("corrupt.docx");
        broken.setContentType("application/msword");
        broken.setFileData(java.util.Base64.getEncoder()
                .encodeToString("not really a document".getBytes(StandardCharsets.UTF_8)));
        when(fileRepo.findById(9L)).thenReturn(Optional.of(broken));
        controller.setDocRendererForTest(new com.churchgeniuspro.service.DocumentHtmlRenderer());

        ResponseEntity<?> r = controller.viewFile(9L, studentKid());
        assertThat(status(r)).isEqualTo(200);
        assertThat(r.getHeaders().getFirst("Content-Type")).isEqualTo("application/msword");
    }

    /** A genuine .docx, so the conversion path is exercised for real. */
    private static byte[] realDocx(String text) throws Exception {
        try (org.apache.poi.xwpf.usermodel.XWPFDocument doc = new org.apache.poi.xwpf.usermodel.XWPFDocument();
             java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream()) {
            doc.createParagraph().createRun().setText(text);
            doc.write(out);
            return out.toByteArray();
        }
    }

    // ══ Documents already in the system are not retroactively exposed ══════

    @Test
    @DisplayName("A file uploaded before this feature is not visible to children")
    void existingFilesDefaultToPrivate() {
        assertThat(new SsFile().isSharedWithStudents())
                .as("an untouched SsFile must not be visible to children")
                .isFalse();
    }

    @Test
    @DisplayName("The student dashboard asks only for documents shared with their class")
    void dashboardQueriesSharedOnly() {
        when(classRepo.findById(JUNIOR)).thenReturn(Optional.of(new SsClass()));
        when(examRepo.findByClassIdAndDeleteFlagFalseOrderByIdDesc(JUNIOR)).thenReturn(List.of());
        when(lessonRepo.findByStudentIdAndDeleteFlagFalseOrderBySortOrderAsc(anyLong())).thenReturn(List.of());
        when(fileRepo.findSharedForClass(JUNIOR)).thenReturn(List.of(file(1, JUNIOR, true)));

        controller.memberDashboard(studentKid());

        verify(fileRepo).findSharedForClass(JUNIOR);
        // never the unfiltered listing, which would include staff-only material
        verify(fileRepo, never()).findByClassIdAndDeleteFlagFalseOrderByUploadedAtDesc(anyLong());
    }

    // ══ File types are enforced on the server, not just in the picker ══════

    @Test
    @DisplayName("An executable is refused even though a crafted request offered it")
    void disallowedTypeRefused() throws Exception {
        MockMultipartFile exe = new MockMultipartFile(
                "file", "payload.exe", "application/octet-stream", "MZ".getBytes(StandardCharsets.UTF_8));
        ResponseEntity<?> r = controller.uploadFile(JUNIOR, exe, true, teacherSam());
        assertThat(status(r)).isEqualTo(400);
        assertThat(String.valueOf(r.getBody())).contains("Allowed file types");
        verify(fileRepo, never()).save(any(SsFile.class));
    }

    @Test
    @DisplayName("Word, PDF, text, slides, sheets and scans are all accepted")
    void allowedTypesAccepted() throws Exception {
        when(fileRepo.save(any(SsFile.class))).thenAnswer(i -> i.getArgument(0));
        for (String name : new String[]{"a.pdf","a.doc","a.docx","a.txt","a.rtf","a.odt",
                                        "a.ppt","a.pptx","a.xls","a.xlsx","a.png","a.JPG"}) {
            MockMultipartFile f = new MockMultipartFile("file", name, null,
                    "x".getBytes(StandardCharsets.UTF_8));
            assertThat(status(controller.uploadFile(JUNIOR, f, true, teacherSam())))
                    .as("should accept %s", name).isEqualTo(200);
        }
    }
}
