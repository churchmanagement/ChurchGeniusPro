package com.churchgeniuspro.controller;

import com.churchgeniuspro.hibernate.*;
import com.churchgeniuspro.repository.*;
import com.churchgeniuspro.service.EmailService;
import com.churchgeniuspro.util.AnswerGrader;
import com.churchgeniuspro.util.RoleGuard;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import jakarta.transaction.Transactional;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.*;
import java.util.stream.Collectors;

/**
 * REST API for Sunday School management.
 * Admin endpoints: /api/ss/*
 * Member (student) endpoints: /api/member/ss/*
 */
@RestController
public class SundaySchoolController {

    private static final Logger log = LoggerFactory.getLogger(SundaySchoolController.class);

    private final SsClassRepository      classRepo;
    private final SsTeacherRepository    teacherRepo;
    private final SsStudentRepository    studentRepo;
    private final SsLessonRepository     lessonRepo;
    private final SsExamRepository       examRepo;
    private final SsQuestionRepository   questionRepo;
    private final SsSubmissionRepository submissionRepo;
    private final SsAnswerRepository     answerRepo;
    private final SsNoteRepository       noteRepo;
    private final SsFileRepository       fileRepo;
    private final FamilyMemberRepository familyMemberRepo;
    private final EmailService           emailService;

    public SundaySchoolController(SsClassRepository classRepo,
                                   SsTeacherRepository teacherRepo,
                                   SsStudentRepository studentRepo,
                                   SsLessonRepository lessonRepo,
                                   SsExamRepository examRepo,
                                   SsQuestionRepository questionRepo,
                                   SsSubmissionRepository submissionRepo,
                                   SsAnswerRepository answerRepo,
                                   SsNoteRepository noteRepo,
                                   SsFileRepository fileRepo,
                                   FamilyMemberRepository familyMemberRepo,
                                   EmailService emailService) {
        this.classRepo      = classRepo;
        this.teacherRepo    = teacherRepo;
        this.studentRepo    = studentRepo;
        this.lessonRepo     = lessonRepo;
        this.examRepo       = examRepo;
        this.questionRepo   = questionRepo;
        this.submissionRepo = submissionRepo;
        this.answerRepo     = answerRepo;
        this.noteRepo       = noteRepo;
        this.fileRepo       = fileRepo;
        this.familyMemberRepo = familyMemberRepo;
        this.emailService   = emailService;
    }

    // =========================================================================
    // ADMIN: CLASSES
    // =========================================================================

    @GetMapping("/api/ss/classes")
    public ResponseEntity<?> getClasses(HttpServletRequest req) {
        String cid = RoleGuard.clientId(req);
        if (cid == null) return ResponseEntity.status(401).build();
        return ResponseEntity.ok(classRepo.findByClientIdAndDeleteFlagFalse(cid));
    }

    @PostMapping("/api/ss/classes")
    public ResponseEntity<?> createClass(@RequestBody Map<String,String> body, HttpServletRequest req) {
        String cid = RoleGuard.clientId(req);
        if (cid == null) return ResponseEntity.status(401).build();
        SsClass c = new SsClass();
        c.setClientId(cid);
        c.setClassName(str(body.get("className")));
        c.setDescription(str(body.get("description")));
        return ResponseEntity.ok(classRepo.save(c));
    }

    @PutMapping("/api/ss/classes/{id}")
    public ResponseEntity<?> updateClass(@PathVariable Long id, @RequestBody Map<String,String> body, HttpServletRequest req) {
        String cid = RoleGuard.clientId(req);
        if (cid == null) return ResponseEntity.status(401).build();
        SsClass c = classRepo.findById(id).orElse(null);
        if (c == null || !c.getClientId().equals(cid)) return ResponseEntity.status(404).build();
        c.setClassName(str(body.get("className")));
        c.setDescription(str(body.get("description")));
        return ResponseEntity.ok(classRepo.save(c));
    }

    @DeleteMapping("/api/ss/classes/{id}")
    public ResponseEntity<?> deleteClass(@PathVariable Long id, HttpServletRequest req) {
        String cid = RoleGuard.clientId(req);
        if (cid == null) return ResponseEntity.status(401).build();
        SsClass c = classRepo.findById(id).orElse(null);
        if (c == null || !c.getClientId().equals(cid)) return ResponseEntity.status(404).build();
        c.setDeleteFlag(true);
        classRepo.save(c);
        return ResponseEntity.ok(Map.of("status", "deleted"));
    }

    // =========================================================================
    // ADMIN: TEACHERS
    // =========================================================================

    @GetMapping("/api/ss/classes/{classId}/teachers")
    public ResponseEntity<?> getTeachers(@PathVariable Long classId, HttpServletRequest req) {
        String cid = RoleGuard.clientId(req);
        if (cid == null) return ResponseEntity.status(401).build();
        return ResponseEntity.ok(teacherRepo.findByClassIdAndDeleteFlagFalse(classId));
    }

    @PostMapping("/api/ss/classes/{classId}/teachers")
    public ResponseEntity<?> addTeacher(@PathVariable Long classId, @RequestBody Map<String,String> body, HttpServletRequest req) {
        String cid = RoleGuard.clientId(req);
        if (cid == null) return ResponseEntity.status(401).build();
        SsTeacher t = new SsTeacher();
        t.setClientId(cid);
        t.setClassId(classId);
        t.setTeacherName(str(body.get("teacherName")));
        t.setEmail(str(body.get("email")));
        t.setMemberRef(str(body.get("memberRef")));
        return ResponseEntity.ok(teacherRepo.save(t));
    }

    @DeleteMapping("/api/ss/teachers/{id}")
    public ResponseEntity<?> deleteTeacher(@PathVariable Long id, HttpServletRequest req) {
        String cid = RoleGuard.clientId(req);
        if (cid == null) return ResponseEntity.status(401).build();
        SsTeacher t = teacherRepo.findById(id).orElse(null);
        if (t == null || !t.getClientId().equals(cid)) return ResponseEntity.status(404).build();
        t.setDeleteFlag(true);
        teacherRepo.save(t);
        return ResponseEntity.ok(Map.of("status", "deleted"));
    }

    // =========================================================================
    // ADMIN: CHILD MEMBERS (for student picker)
    // =========================================================================

    @GetMapping("/api/ss/child-members")
    public ResponseEntity<?> getChildMembers(HttpServletRequest req) {
        String cid = RoleGuard.clientId(req);
        if (cid == null) return ResponseEntity.status(401).build();
        List<FamilyMember> all = familyMemberRepo.findAllWithFamilyByAppUser(cid);
        List<Map<String,Object>> children = all.stream()
            .filter(m -> "Child".equalsIgnoreCase(m.getRole())
                      || "Son".equalsIgnoreCase(m.getRole())
                      || "Daughter".equalsIgnoreCase(m.getRole()))
            .map(m -> {
                Map<String,Object> r = new LinkedHashMap<>();
                r.put("id", m.getId());
                r.put("name", (m.getFirstName() != null ? m.getFirstName().trim() : "") + " " + (m.getLastName() != null ? m.getLastName().trim() : ""));
                r.put("email", m.getEmail() != null ? m.getEmail() : "");
                r.put("memberRef", m.getMemberRef() != null ? m.getMemberRef() : "");
                r.put("familyId", m.getFamily() != null ? m.getFamily().getId() : null);
                // Head of Household email fallback
                String hohEmail = "";
                if ((m.getEmail() == null || m.getEmail().isBlank()) && m.getFamily() != null) {
                    hohEmail = familyMemberRepo.findActiveMembersByFamilyId(m.getFamily().getId())
                        .stream()
                        .filter(hm -> "Head".equalsIgnoreCase(hm.getRole()) || "Head of Household".equalsIgnoreCase(hm.getRole()))
                        .map(FamilyMember::getEmail)
                        .filter(e -> e != null && !e.isBlank())
                        .findFirst().orElse("");
                }
                r.put("hohEmail", hohEmail);
                return r;
            })
            .collect(Collectors.toList());
        return ResponseEntity.ok(children);
    }

    // =========================================================================
    // ADMIN: STUDENTS
    // =========================================================================

    @GetMapping("/api/ss/classes/{classId}/students")
    public ResponseEntity<?> getStudents(@PathVariable Long classId, HttpServletRequest req) {
        String cid = RoleGuard.clientId(req);
        if (cid == null) return ResponseEntity.status(401).build();
        return ResponseEntity.ok(studentRepo.findByClassIdAndDeleteFlagFalse(classId));
    }

    @PostMapping("/api/ss/classes/{classId}/students")
    public ResponseEntity<?> addStudent(@PathVariable Long classId, @RequestBody Map<String,Object> body, HttpServletRequest req) {
        String cid = RoleGuard.clientId(req);
        if (cid == null) return ResponseEntity.status(401).build();
        SsStudent s = new SsStudent();
        s.setClientId(cid);
        s.setClassId(classId);
        s.setTeacherId(Long.parseLong(str(body.get("teacherId"))));
        s.setStudentName(str(body.get("studentName")));
        s.setContactEmail(str(body.get("contactEmail")));
        s.setMemberRef(str(body.get("memberRef")));
        if (body.get("familyMemberId") != null) {
            try { s.setFamilyMemberId(Integer.parseInt(str(body.get("familyMemberId")))); } catch (Exception ignored) {}
        }
        return ResponseEntity.ok(studentRepo.save(s));
    }

    @DeleteMapping("/api/ss/students/{id}")
    public ResponseEntity<?> deleteStudent(@PathVariable Long id, HttpServletRequest req) {
        String cid = RoleGuard.clientId(req);
        if (cid == null) return ResponseEntity.status(401).build();
        SsStudent s = studentRepo.findById(id).orElse(null);
        if (s == null || !s.getClientId().equals(cid)) return ResponseEntity.status(404).build();
        s.setDeleteFlag(true);
        studentRepo.save(s);
        return ResponseEntity.ok(Map.of("status", "deleted"));
    }

    /**
     * Re-link a student record to a different family member.
     * Body: { "familyMemberId": 147, "memberRef": "MBRdf511aa2-..." }
     * Used by admins to fix mis-linked enrollments without direct DB access.
     */
    @PutMapping("/api/ss/students/{id}/link-member")
    public ResponseEntity<?> linkStudentToMember(
            @PathVariable Long id,
            @RequestBody Map<String, Object> body,
            HttpServletRequest req) {
        String cid = RoleGuard.clientId(req);
        if (cid == null) return ResponseEntity.status(401).build();
        SsStudent s = studentRepo.findById(id).orElse(null);
        if (s == null || !s.getClientId().equals(cid)) return ResponseEntity.status(404).build();

        if (body.containsKey("familyMemberId") && body.get("familyMemberId") != null) {
            try { s.setFamilyMemberId(Integer.parseInt(str(body.get("familyMemberId")))); }
            catch (NumberFormatException ignored) {}
        } else {
            s.setFamilyMemberId(null);
        }
        String memberRef = str(body.get("memberRef"));
        s.setMemberRef(memberRef != null && !memberRef.isBlank() ? memberRef : null);

        studentRepo.save(s);
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("status",        "linked");
        resp.put("familyMemberId", s.getFamilyMemberId());   // may be null
        resp.put("memberRef",      s.getMemberRef() != null ? s.getMemberRef() : "");
        return ResponseEntity.ok(resp);
    }

    // =========================================================================
    // ADMIN: LESSONS
    // =========================================================================

    @GetMapping("/api/ss/students/{studentId}/lessons")
    public ResponseEntity<?> getLessons(@PathVariable Long studentId, HttpServletRequest req) {
        String cid = RoleGuard.clientId(req);
        if (cid == null) return ResponseEntity.status(401).build();
        return ResponseEntity.ok(lessonRepo.findByStudentIdAndDeleteFlagFalseOrderBySortOrderAsc(studentId));
    }

    @PostMapping("/api/ss/students/{studentId}/lessons")
    public ResponseEntity<?> addLesson(@PathVariable Long studentId, @RequestBody Map<String,Object> body, HttpServletRequest req) {
        String cid = RoleGuard.clientId(req);
        if (cid == null) return ResponseEntity.status(401).build();
        SsStudent stu = studentRepo.findById(studentId).orElse(null);
        if (stu == null) return ResponseEntity.status(404).build();
        SsLesson l = new SsLesson();
        l.setClientId(cid);
        l.setClassId(stu.getClassId());
        l.setStudentId(studentId);
        l.setLessonTitle(str(body.get("lessonTitle")));
        l.setStatus(str(body.getOrDefault("status", "Pending")));
        l.setRemarks(str(body.get("remarks")));
        List<SsLesson> existing = lessonRepo.findByStudentIdAndDeleteFlagFalseOrderBySortOrderAsc(studentId);
        l.setSortOrder(existing.size() + 1);
        return ResponseEntity.ok(lessonRepo.save(l));
    }

    @PatchMapping("/api/ss/lessons/{id}")
    public ResponseEntity<?> updateLesson(@PathVariable Long id, @RequestBody Map<String,Object> body, HttpServletRequest req) {
        String cid = RoleGuard.clientId(req);
        if (cid == null) return ResponseEntity.status(401).build();
        SsLesson l = lessonRepo.findById(id).orElse(null);
        if (l == null || !l.getClientId().equals(cid)) return ResponseEntity.status(404).build();
        if (body.containsKey("status"))      l.setStatus(str(body.get("status")));
        if (body.containsKey("remarks"))     l.setRemarks(str(body.get("remarks")));
        if (body.containsKey("lessonTitle")) l.setLessonTitle(str(body.get("lessonTitle")));
        return ResponseEntity.ok(lessonRepo.save(l));
    }

    @DeleteMapping("/api/ss/lessons/{id}")
    public ResponseEntity<?> deleteLesson(@PathVariable Long id, HttpServletRequest req) {
        String cid = RoleGuard.clientId(req);
        if (cid == null) return ResponseEntity.status(401).build();
        SsLesson l = lessonRepo.findById(id).orElse(null);
        if (l == null || !l.getClientId().equals(cid)) return ResponseEntity.status(404).build();
        l.setDeleteFlag(true);
        lessonRepo.save(l);
        return ResponseEntity.ok(Map.of("status", "deleted"));
    }

    // =========================================================================
    // ADMIN: NOTES
    // =========================================================================

    @GetMapping("/api/ss/classes/{classId}/notes")
    public ResponseEntity<?> getNotes(@PathVariable Long classId, HttpServletRequest req) {
        String cid = RoleGuard.clientId(req);
        if (cid == null) return ResponseEntity.status(401).build();
        return ResponseEntity.ok(noteRepo.findByClassIdAndDeleteFlagFalseOrderByCreatedAtDesc(classId));
    }

    @PostMapping("/api/ss/classes/{classId}/notes")
    public ResponseEntity<?> addNote(@PathVariable Long classId, @RequestBody Map<String,String> body, HttpServletRequest req) {
        String cid = RoleGuard.clientId(req);
        if (cid == null) return ResponseEntity.status(401).build();
        SsNote n = new SsNote();
        n.setClientId(cid);
        n.setClassId(classId);
        n.setNoteText(str(body.get("noteText")));
        n.setCreatedAt(LocalDateTime.now());
        return ResponseEntity.ok(noteRepo.save(n));
    }

    @DeleteMapping("/api/ss/notes/{id}")
    public ResponseEntity<?> deleteNote(@PathVariable Long id, HttpServletRequest req) {
        String cid = RoleGuard.clientId(req);
        if (cid == null) return ResponseEntity.status(401).build();
        SsNote n = noteRepo.findById(id).orElse(null);
        if (n == null || !n.getClientId().equals(cid)) return ResponseEntity.status(404).build();
        n.setDeleteFlag(true);
        noteRepo.save(n);
        return ResponseEntity.ok(Map.of("status", "deleted"));
    }

    // =========================================================================
    // ADMIN: FILES
    // =========================================================================

    @GetMapping("/api/ss/classes/{classId}/files")
    public ResponseEntity<?> getFiles(@PathVariable Long classId, HttpServletRequest req) {
        String cid = RoleGuard.clientId(req);
        if (cid == null) return ResponseEntity.status(401).build();
        List<SsFile> files = fileRepo.findByClassIdAndDeleteFlagFalseOrderByUploadedAtDesc(classId);
        // Return without fileData for listing
        List<Map<String,Object>> result = files.stream().map(f -> {
            Map<String,Object> r = new LinkedHashMap<>();
            r.put("id", f.getId());
            r.put("originalName", f.getOriginalName());
            r.put("contentType", f.getContentType());
            r.put("uploadedAt", f.getUploadedAt());
            return r;
        }).collect(Collectors.toList());
        return ResponseEntity.ok(result);
    }

    @PostMapping("/api/ss/classes/{classId}/files")
    public ResponseEntity<?> uploadFile(@PathVariable Long classId,
                                        @RequestParam("file") MultipartFile file,
                                        HttpServletRequest req) throws IOException {
        String cid = RoleGuard.clientId(req);
        if (cid == null) return ResponseEntity.status(401).build();
        SsFile f = new SsFile();
        f.setClientId(cid);
        f.setClassId(classId);
        f.setOriginalName(file.getOriginalFilename());
        f.setContentType(file.getContentType());
        f.setFileData(Base64.getEncoder().encodeToString(file.getBytes()));
        f.setUploadedAt(LocalDateTime.now());
        fileRepo.save(f);
        return ResponseEntity.ok(Map.of("status", "uploaded", "id", f.getId(), "name", f.getOriginalName()));
    }

    @GetMapping("/api/ss/files/{id}/download")
    public ResponseEntity<?> downloadFile(@PathVariable Long id, HttpServletRequest req) {
        String cid = RoleGuard.clientId(req);
        if (cid == null) return ResponseEntity.status(401).build();
        SsFile f = fileRepo.findById(id).orElse(null);
        if (f == null || !f.getClientId().equals(cid)) return ResponseEntity.status(404).build();
        byte[] data = Base64.getDecoder().decode(f.getFileData());
        return org.springframework.http.ResponseEntity.ok()
            .header("Content-Disposition", "attachment; filename=\"" + f.getOriginalName() + "\"")
            .header("Content-Type", f.getContentType() != null ? f.getContentType() : "application/octet-stream")
            .body(data);
    }

    @DeleteMapping("/api/ss/files/{id}")
    public ResponseEntity<?> deleteFile(@PathVariable Long id, HttpServletRequest req) {
        String cid = RoleGuard.clientId(req);
        if (cid == null) return ResponseEntity.status(401).build();
        SsFile f = fileRepo.findById(id).orElse(null);
        if (f == null || !f.getClientId().equals(cid)) return ResponseEntity.status(404).build();
        f.setDeleteFlag(true);
        fileRepo.save(f);
        return ResponseEntity.ok(Map.of("status", "deleted"));
    }

    // =========================================================================
    // ADMIN: EXAMS
    // =========================================================================

    @GetMapping("/api/ss/classes/{classId}/exams")
    public ResponseEntity<?> getExams(@PathVariable Long classId, HttpServletRequest req) {
        String cid = RoleGuard.clientId(req);
        if (cid == null) return ResponseEntity.status(401).build();
        return ResponseEntity.ok(examRepo.findByClassIdAndDeleteFlagFalseOrderByIdDesc(classId));
    }

    @PostMapping("/api/ss/classes/{classId}/exams")
    public ResponseEntity<?> createExam(@PathVariable Long classId, @RequestBody Map<String,Object> body, HttpServletRequest req) {
        String cid = RoleGuard.clientId(req);
        if (cid == null) return ResponseEntity.status(401).build();
        SsExam e = new SsExam();
        e.setClientId(cid);
        e.setClassId(classId);
        e.setExamTitle(str(body.get("examTitle")));
        e.setStatus("Draft");
        if (body.get("totalQuestions") != null) e.setTotalQuestions(Integer.parseInt(str(body.get("totalQuestions"))));
        if (body.get("requiredQuestions") != null) e.setRequiredQuestions(Integer.parseInt(str(body.get("requiredQuestions"))));
        if (body.get("durationMinutes") != null) e.setDurationMinutes(Integer.parseInt(str(body.get("durationMinutes"))));
        return ResponseEntity.ok(examRepo.save(e));
    }

    @PutMapping("/api/ss/exams/{id}")
    public ResponseEntity<?> updateExam(@PathVariable Long id, @RequestBody Map<String,Object> body, HttpServletRequest req) {
        String cid = RoleGuard.clientId(req);
        if (cid == null) return ResponseEntity.status(401).build();
        SsExam e = examRepo.findById(id).orElse(null);
        if (e == null || !e.getClientId().equals(cid)) return ResponseEntity.status(404).build();
        if (body.containsKey("examTitle"))       e.setExamTitle(str(body.get("examTitle")));
        if (body.containsKey("status"))          e.setStatus(str(body.get("status")));
        if (body.containsKey("totalQuestions") && body.get("totalQuestions") != null)
            e.setTotalQuestions(Integer.parseInt(str(body.get("totalQuestions"))));
        if (body.containsKey("requiredQuestions") && body.get("requiredQuestions") != null)
            e.setRequiredQuestions(Integer.parseInt(str(body.get("requiredQuestions"))));
        if (body.containsKey("durationMinutes") && body.get("durationMinutes") != null)
            e.setDurationMinutes(Integer.parseInt(str(body.get("durationMinutes"))));
        if (body.containsKey("copyToHoh"))
            e.setCopyToHoh(Boolean.TRUE.equals(body.get("copyToHoh")) || "true".equalsIgnoreCase(str(body.get("copyToHoh"))));
        return ResponseEntity.ok(examRepo.save(e));
    }

    @DeleteMapping("/api/ss/exams/{id}")
    public ResponseEntity<?> deleteExam(@PathVariable Long id, HttpServletRequest req) {
        String cid = RoleGuard.clientId(req);
        if (cid == null) return ResponseEntity.status(401).build();
        SsExam e = examRepo.findById(id).orElse(null);
        if (e == null || !e.getClientId().equals(cid)) return ResponseEntity.status(404).build();
        e.setDeleteFlag(true);
        examRepo.save(e);
        return ResponseEntity.ok(Map.of("status", "deleted"));
    }

    /**
     * Admin: republish an exam so students can retake it.
     * Sets status → Published and soft-deletes all existing submissions + answers.
     */
    @PostMapping("/api/ss/exams/{id}/republish")
    @Transactional
    public ResponseEntity<?> republishExam(@PathVariable Long id, HttpServletRequest req) {
        String cid = RoleGuard.clientId(req);
        if (cid == null) return ResponseEntity.status(401).build();
        SsExam e = examRepo.findById(id).orElse(null);
        if (e == null || !e.getClientId().equals(cid)) return ResponseEntity.status(404).build();
        // Soft-delete all submissions (and their answers) so students get a clean slate
        List<SsSubmission> subs = submissionRepo.findByExamIdAndDeleteFlagFalse(id);
        for (SsSubmission sub : subs) {
            List<SsAnswer> answers = answerRepo.findBySubmissionId(sub.getId());
            answerRepo.deleteAll(answers);
            sub.setDeleteFlag(true);
            submissionRepo.save(sub);
        }
        e.setStatus("Published");
        examRepo.save(e);
        return ResponseEntity.ok(Map.of("status", "republished", "clearedSubmissions", subs.size()));
    }

    // =========================================================================
    // ADMIN: QUESTIONS
    // =========================================================================

    @GetMapping("/api/ss/exams/{examId}/questions")
    public ResponseEntity<?> getQuestions(@PathVariable Long examId, HttpServletRequest req) {
        String cid = RoleGuard.clientId(req);
        if (cid == null) return ResponseEntity.status(401).build();
        return ResponseEntity.ok(questionRepo.findByExamIdAndDeleteFlagFalseOrderBySortOrderAsc(examId));
    }

    @PostMapping("/api/ss/exams/{examId}/questions")
    public ResponseEntity<?> addQuestion(@PathVariable Long examId, @RequestBody Map<String,Object> body, HttpServletRequest req) {
        String cid = RoleGuard.clientId(req);
        if (cid == null) return ResponseEntity.status(401).build();
        SsQuestion q = new SsQuestion();
        q.setClientId(cid);
        q.setExamId(examId);
        q.setQuestionType(str(body.get("questionType")));
        q.setQuestionText(str(body.get("questionText")));
        q.setOptionsJson(str(body.get("optionsJson")));
        q.setCorrectAnswer(str(body.get("correctAnswer")));
        if (body.get("marks") != null) q.setMarks(Integer.parseInt(str(body.get("marks"))));
        List<SsQuestion> existing = questionRepo.findByExamIdAndDeleteFlagFalseOrderBySortOrderAsc(examId);
        q.setSortOrder(existing.size() + 1);
        return ResponseEntity.ok(questionRepo.save(q));
    }

    @PutMapping("/api/ss/questions/{id}")
    public ResponseEntity<?> updateQuestion(@PathVariable Long id, @RequestBody Map<String,Object> body, HttpServletRequest req) {
        String cid = RoleGuard.clientId(req);
        if (cid == null) return ResponseEntity.status(401).build();
        SsQuestion q = questionRepo.findById(id).orElse(null);
        if (q == null || !q.getClientId().equals(cid)) return ResponseEntity.status(404).build();
        if (body.containsKey("questionText"))  q.setQuestionText(str(body.get("questionText")));
        if (body.containsKey("questionType"))  q.setQuestionType(str(body.get("questionType")));
        if (body.containsKey("optionsJson"))   q.setOptionsJson(str(body.get("optionsJson")));
        if (body.containsKey("correctAnswer")) q.setCorrectAnswer(str(body.get("correctAnswer")));
        if (body.containsKey("marks") && body.get("marks") != null)
            q.setMarks(Integer.parseInt(str(body.get("marks"))));
        return ResponseEntity.ok(questionRepo.save(q));
    }

    @DeleteMapping("/api/ss/questions/{id}")
    public ResponseEntity<?> deleteQuestion(@PathVariable Long id, HttpServletRequest req) {
        String cid = RoleGuard.clientId(req);
        if (cid == null) return ResponseEntity.status(401).build();
        SsQuestion q = questionRepo.findById(id).orElse(null);
        if (q == null || !q.getClientId().equals(cid)) return ResponseEntity.status(404).build();
        q.setDeleteFlag(true);
        questionRepo.save(q);
        return ResponseEntity.ok(Map.of("status", "deleted"));
    }

    // =========================================================================
    // ADMIN: UPLOAD & PARSE QUESTION PAPER
    // =========================================================================

    @PostMapping("/api/ss/exams/{examId}/upload-paper")
    public ResponseEntity<?> uploadQuestionPaper(@PathVariable Long examId,
                                                  @RequestParam("file") MultipartFile file,
                                                  HttpServletRequest req) throws IOException {
        String cid = RoleGuard.clientId(req);
        if (cid == null) return ResponseEntity.status(401).build();
        SsExam exam = examRepo.findById(examId).orElse(null);
        if (exam == null || !exam.getClientId().equals(cid)) return ResponseEntity.status(404).build();

        String text = extractText(file);
        List<Map<String,Object>> parsed = parseQuestions(text);

        // Delete old questions and insert parsed
        List<SsQuestion> old = questionRepo.findByExamIdAndDeleteFlagFalseOrderBySortOrderAsc(examId);
        old.forEach(q -> { q.setDeleteFlag(true); questionRepo.save(q); });

        int order = 1;
        List<SsQuestion> saved = new ArrayList<>();
        for (Map<String,Object> pq : parsed) {
            SsQuestion q = new SsQuestion();
            q.setClientId(cid);
            q.setExamId(examId);
            q.setQuestionType(str(pq.get("type")));
            q.setQuestionText(str(pq.get("text")));
            q.setOptionsJson(str(pq.get("options")));
            q.setCorrectAnswer(str(pq.get("answer")));
            q.setMarks("Comprehensive".equals(str(pq.get("type"))) ? 5 : 1);
            q.setSortOrder(order++);
            saved.add(questionRepo.save(q));
        }
        exam.setTotalQuestions(saved.size());
        examRepo.save(exam);
        return ResponseEntity.ok(Map.of("status", "parsed", "count", saved.size(), "questions", saved));
    }

    // =========================================================================
    // ADMIN: SUBMISSIONS & EVALUATION
    // =========================================================================

    @GetMapping("/api/ss/exams/{examId}/submissions")
    public ResponseEntity<?> getSubmissions(@PathVariable Long examId, HttpServletRequest req) {
        String cid = RoleGuard.clientId(req);
        if (cid == null) return ResponseEntity.status(401).build();
        List<SsSubmission> subs = submissionRepo.findByExamIdAndDeleteFlagFalse(examId);
        SsExam exam = examRepo.findById(examId).orElse(null);
        List<Map<String,Object>> result = subs.stream().map(s -> {
            Map<String,Object> r = new LinkedHashMap<>();
            r.put("id", s.getId());
            r.put("studentId", s.getStudentId());
            r.put("status", s.getStatus());
            r.put("startedAt", s.getStartedAt());
            r.put("submittedAt", s.getSubmittedAt());
            r.put("autoMarks", s.getAutoMarks());
            r.put("manualMarks", s.getManualMarks());
            r.put("totalMarks", s.getTotalMarks());
            r.put("durationMinutes", exam != null ? exam.getDurationMinutes() : null);
            r.put("extraTimeMinutes", s.getExtraTimeMinutes());
            if (s.getStartedAt() != null && s.getSubmittedAt() != null) {
                long mins = java.time.Duration.between(s.getStartedAt(), s.getSubmittedAt()).toMinutes();
                r.put("takenMinutes", mins);
            }
            studentRepo.findById(s.getStudentId()).ifPresent(st -> r.put("studentName", st.getStudentName()));
            return r;
        }).collect(Collectors.toList());
        return ResponseEntity.ok(result);
    }

    @GetMapping("/api/ss/submissions/{submissionId}/answers")
    public ResponseEntity<?> getAnswers(@PathVariable Long submissionId, HttpServletRequest req) {
        String cid = RoleGuard.clientId(req);
        if (cid == null) return ResponseEntity.status(401).build();
        SsSubmission sub = submissionRepo.findById(submissionId).orElse(null);
        if (sub == null) return ResponseEntity.status(404).build();
        List<SsQuestion> questions = questionRepo.findByExamIdAndDeleteFlagFalseOrderBySortOrderAsc(sub.getExamId());
        List<SsAnswer> answers = answerRepo.findBySubmissionId(submissionId);
        Map<Long,SsAnswer> ansMap = new java.util.HashMap<>();
        for (SsAnswer ans0 : answers) { ansMap.put(ans0.getQuestionId(), ans0); }
        List<Map<String,Object>> items = questions.stream().map(q -> {
            Map<String,Object> r = new LinkedHashMap<>();
            r.put("questionId", q.getId());
            r.put("questionText", q.getQuestionText());
            r.put("questionType", q.getQuestionType());
            r.put("optionsJson", q.getOptionsJson());
            r.put("correctAnswer", q.getCorrectAnswer());
            r.put("marks", q.getMarks());
            r.put("sortOrder", q.getSortOrder());
            SsAnswer a = ansMap.get(q.getId());
            r.put("answerId", a != null ? a.getId() : null);
            r.put("answerText", a != null ? a.getAnswerText() : null);
            r.put("isCorrect", a != null ? a.getIsCorrect() : null);
            r.put("manualMarks", a != null ? a.getManualMarks() : null);
            return r;
        }).collect(Collectors.toList());
        String studentName = studentRepo.findById(sub.getStudentId()).map(st -> st.getStudentName()).orElse("");
        Map<String,Object> resp0 = new LinkedHashMap<>();
        resp0.put("submissionId", submissionId);
        resp0.put("studentName",  studentName);
        resp0.put("autoMarks",    sub.getAutoMarks()   != null ? sub.getAutoMarks()   : 0);
        resp0.put("manualMarks",  sub.getManualMarks() != null ? sub.getManualMarks() : 0);
        resp0.put("totalMarks",   sub.getTotalMarks()  != null ? sub.getTotalMarks()  : 0);
        resp0.put("questions",    items);
        return ResponseEntity.ok(resp0);
    }

    /** Teacher assigns manual marks to a specific answer. */
    @PatchMapping("/api/ss/answers/{answerId}/marks")
    public ResponseEntity<?> setManualMarks(@PathVariable Long answerId,
                                             @RequestBody Map<String,Object> body,
                                             HttpServletRequest req) {
        String cid = RoleGuard.clientId(req);
        if (cid == null) return ResponseEntity.status(401).build();
        SsAnswer a = answerRepo.findById(answerId).orElse(null);
        if (a == null) return ResponseEntity.status(404).build();
        int marks = Integer.parseInt(str(body.get("manualMarks")));
        a.setManualMarks(marks);
        answerRepo.save(a);
        // Recalculate total on submission
        recalcTotal(a.getSubmissionId());
        return ResponseEntity.ok(Map.of("status", "ok"));
    }

    /** Admin: override total marks on a submission directly. */
    @PatchMapping("/api/ss/submissions/{submissionId}/marks")
    public ResponseEntity<?> setSubmissionTotalMarks(@PathVariable Long submissionId,
                                                      @RequestBody Map<String,Object> body,
                                                      HttpServletRequest req) {
        String cid = RoleGuard.clientId(req);
        if (cid == null) return ResponseEntity.status(401).build();
        SsSubmission sub = submissionRepo.findById(submissionId).orElse(null);
        if (sub == null) return ResponseEntity.status(404).build();
        int total = Integer.parseInt(str(body.get("totalMarks")));
        sub.setTotalMarks(total);
        submissionRepo.save(sub);
        return ResponseEntity.ok(Map.of("status", "ok"));
    }

    /** Admin: extend time on an InProgress submission. */
    @PostMapping("/api/ss/submissions/{submissionId}/extend")
    public ResponseEntity<?> adminExtendTime(@PathVariable Long submissionId,
                                              @RequestBody Map<String,Object> body,
                                              HttpServletRequest req) {
        String cid = RoleGuard.clientId(req);
        if (cid == null) return ResponseEntity.status(401).build();
        SsSubmission sub = submissionRepo.findById(submissionId).orElse(null);
        if (sub == null) return ResponseEntity.status(404).build();
        if (!"InProgress".equals(sub.getStatus()))
            return ResponseEntity.badRequest().body(Map.of("error", "Exam is not in progress."));
        int extra = Integer.parseInt(str(body.getOrDefault("extraMinutes", "0")));
        if (extra <= 0) return ResponseEntity.badRequest().body(Map.of("error", "extraMinutes must be > 0"));
        sub.setExtraTimeMinutes(sub.getExtraTimeMinutes() + extra);
        submissionRepo.save(sub);
        return ResponseEntity.ok(Map.of("extraTimeMinutes", sub.getExtraTimeMinutes()));
    }

    // =========================================================================
    // ADMIN: SEND GRADED RESULT TO PARENT / HOH
    // =========================================================================

    /**
     * Admin: email the finalized graded answer sheet to the student's contact email
     * and/or the Head-of-Household.  Also marks the submission as reviewed so the
     * student portal can display the final result.
     *
     * Request body (all optional):
     *   { "includeAnswerSheet": true }   — default true, sends full Q&A breakdown
     */
    @PostMapping("/api/ss/submissions/{submissionId}/send-result")
    @Transactional
    public ResponseEntity<?> adminSendResult(@PathVariable Long submissionId,
                                              @RequestBody(required = false) Map<String,Object> body,
                                              HttpServletRequest req) {
        String cid = RoleGuard.clientId(req);
        if (cid == null) return ResponseEntity.status(401).build();

        SsSubmission sub = submissionRepo.findById(submissionId).orElse(null);
        if (sub == null) return ResponseEntity.status(404).build();

        SsExam exam = examRepo.findById(sub.getExamId()).orElse(null);
        if (exam == null) return ResponseEntity.status(404).build();

        SsStudent student = studentRepo.findById(sub.getStudentId()).orElse(null);
        if (student == null) return ResponseEntity.status(404).build();

        List<SsQuestion> questions = questionRepo.findByExamIdAndDeleteFlagFalseOrderBySortOrderAsc(sub.getExamId());
        List<SsAnswer>   answers   = answerRepo.findBySubmissionId(submissionId);
        Map<Long,SsAnswer> ansMap  = new java.util.HashMap<>();
        for (SsAnswer a : answers) ansMap.put(a.getQuestionId(), a);

        // Mark as reviewed so student portal shows final result
        sub.setReviewed(true);
        if (!"Graded".equals(sub.getStatus())) sub.setStatus("Graded");
        submissionRepo.save(sub);

        String subject = exam.getExamTitle() + " — Graded Result for " + student.getStudentName();
        String html    = buildGradedResultEmail(student, exam, sub, questions, ansMap);

        int sent = 0;
        List<String> errors = new ArrayList<>();

        // Send to student contact email
        String studentEmail = student.getContactEmail();
        if (studentEmail != null && !studentEmail.isBlank()) {
            try {
                emailService.sendOrgEmail(studentEmail.trim(), subject, html, sub.getClientId());
                sent++;
            } catch (Exception ex) {
                errors.add("Student email failed: " + ex.getMessage());
                log.error("Failed to send graded result to student email", ex);
            }
        }

        // Send to Head-of-Household
        if (student.getFamilyMemberId() != null) {
            try {
                String hohEmail = familyMemberRepo.findHohEmailByFamilyMemberId(student.getFamilyMemberId());
                if (hohEmail != null && !hohEmail.isBlank() &&
                        !hohEmail.equalsIgnoreCase(studentEmail)) {
                    emailService.sendOrgEmail(hohEmail.trim(), subject, html, sub.getClientId());
                    sent++;
                }
            } catch (Exception ex) {
                errors.add("HoH email failed: " + ex.getMessage());
                log.error("Failed to send graded result to HoH", ex);
            }
        }

        Map<String,Object> resp = new LinkedHashMap<>();
        resp.put("status", errors.isEmpty() ? "sent" : "partial");
        resp.put("emailsSent", sent);
        if (!errors.isEmpty()) resp.put("errors", errors);
        return ResponseEntity.ok(resp);
    }

    // =========================================================================
    // STUDENT ALERT TO TEACHER
    // =========================================================================

    @PostMapping("/api/member/ss/alert")
    public ResponseEntity<?> studentAlert(@RequestBody(required = false) Map<String,Object> body, HttpServletRequest req) {
        HttpSession session = req.getSession(false);
        if (session == null) return ResponseEntity.status(401).build();
        String[] ssId = resolveSsIdentity(session);
        if (ssId == null) return ResponseEntity.ok(Map.of("success", false, "error", "Not a member account"));
        String memberRef = ssId[0];
        String clientId  = ssId[1];

        if (body == null) body = Collections.emptyMap();
        String examIdStr = str(body.get("examId"));
        String message = str(body.get("message"));
        String type = str(body.getOrDefault("type", "alert")); // "alert" or "question"

        if (message == null || message.isBlank())
            return ResponseEntity.badRequest().body(Map.of("error", "Message is required."));

        // examId is optional — alert still sends without it
        SsExam exam = null;
        if (examIdStr != null) {
            try { exam = examRepo.findById(Long.parseLong(examIdStr)).orElse(null); }
            catch (NumberFormatException ignored) {}
        }

        // Find the student to get teacher (resilient — falls back to familyMemberId lookup)
        SsStudent student = resolveStudent(session);
        if (student == null) return ResponseEntity.status(404).build();

        SsTeacher teacher = teacherRepo.findById(student.getTeacherId()).orElse(null);
        if (teacher == null || teacher.getEmail() == null || teacher.getEmail().isBlank())
            return ResponseEntity.ok(Map.of("status", "no_teacher_email"));

        String examTitle = exam != null ? exam.getExamTitle() : null;
        String subject = (type.equals("question") ? "📚 Student Question" : "🔔 Student Alert")
                + (examTitle != null ? " — " + examTitle : "");
        String html = "<p><strong>Student:</strong> " + esc(student.getStudentName()) + "</p>"
                + (examTitle != null ? "<p><strong>Exam:</strong> " + esc(examTitle) + "</p>" : "")
                + "<p><strong>Message:</strong> " + esc(message) + "</p>";
        try {
            emailService.sendOrgEmail(teacher.getEmail(), subject, html, clientId);
        } catch (Exception e) {
            log.error("Failed to send student alert email", e);
        }
        return ResponseEntity.ok(Map.of("status", "sent"));
    }

    // =========================================================================
    // MEMBER (STUDENT) ENDPOINTS
    // =========================================================================

    /**
     * TEMPORARY DEBUG — remove after diagnosis.
     * GET /api/member/ss/debug while logged in as Christina to see live DB state.
     */
    @GetMapping("/api/member/ss/debug")
    public ResponseEntity<?> debugSsLookup(HttpServletRequest req) {
        HttpSession session = req.getSession(false);
        if (session == null) return ResponseEntity.status(401).body("No session");
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("session_role",       session.getAttribute("role"));
        out.put("session_clientId",   session.getAttribute("clientId"));
        out.put("session_appClientId",session.getAttribute("appClientId"));
        out.put("session_memberId",   session.getAttribute("memberId"));
        out.put("session_memberRef",  session.getAttribute("memberRef"));
        String[] ssId = resolveSsIdentity(session);
        out.put("resolveSsIdentity",  ssId != null ? java.util.Arrays.asList(ssId) : null);
        if (ssId == null) { out.put("note", "not a member session"); return ResponseEntity.ok(out); }
        String memberRef = ssId[0];
        String clientId  = ssId[1];
        Object midObj = session.getAttribute("memberId");
        FamilyMember fm = null;
        if (midObj instanceof Number) fm = familyMemberRepo.findById(((Number) midObj).intValue()).orElse(null);
        if (fm == null) fm = familyMemberRepo.findByMemberRef(memberRef).orElse(null);
        if (fm != null) {
            Map<String,Object> fmMap = new LinkedHashMap<>();
            fmMap.put("id", fm.getId()); fmMap.put("firstName", fm.getFirstName());
            fmMap.put("lastName", fm.getLastName()); fmMap.put("email", fm.getEmail());
            fmMap.put("memberRef", fm.getMemberRef()); fmMap.put("appClientId", fm.getAppClientId());
            out.put("familyMember", fmMap);
        } else { out.put("familyMember", "NOT FOUND"); }
        // Strategy results
        out.put("s1_memberRef", memberRef); out.put("s1_clientId", clientId);
        SsStudent s1 = studentRepo.findByMemberRefAndClientIdAndDeleteFlagFalse(memberRef, clientId).orElse(null);
        out.put("s1_result", s1 != null ? debugStudentSummary(s1) : "NO MATCH");
        if (midObj instanceof Number) {
            Integer fmid = ((Number) midObj).intValue();
            out.put("s2_familyMemberId", fmid);
            SsStudent s2 = studentRepo.findByFamilyMemberIdAndClientIdAndDeleteFlagFalse(fmid, clientId).orElse(null);
            out.put("s2_result", s2 != null ? debugStudentSummary(s2) : "NO MATCH");
            List<SsStudent> s2b = studentRepo.findByFamilyMemberIdAndDeleteFlagFalse(fmid);
            out.put("s2b_result", s2b.isEmpty() ? "NO MATCH" : s2b.stream().map(this::debugStudentSummary).collect(Collectors.toList()));
        }
        List<SsStudent> s3 = studentRepo.findByMemberRefAndDeleteFlagFalse(memberRef);
        out.put("s3_result", s3.isEmpty() ? "NO MATCH" : s3.stream().map(this::debugStudentSummary).collect(Collectors.toList()));
        FamilyMember fm4 = familyMemberRepo.findByMemberRef(memberRef).orElse(null);
        if (fm4 != null) {
            List<SsStudent> s4 = studentRepo.findByFamilyMemberIdAndDeleteFlagFalse(fm4.getId());
            out.put("s4_fm_id", fm4.getId());
            out.put("s4_result", s4.isEmpty() ? "NO MATCH" : s4.stream().map(this::debugStudentSummary).collect(Collectors.toList()));
        } else { out.put("s4_result", "NO FM ROW BY memberRef"); }
        // All org students
        if (clientId != null && !clientId.toUpperCase().startsWith("MBR")) {
            List<SsStudent> all = studentRepo.findByClientIdAndDeleteFlagFalse(clientId);
            out.put("all_org_students", all.stream().map(this::debugStudentSummary).collect(Collectors.toList()));
        }
        return ResponseEntity.ok(out);
    }

    private Map<String, Object> debugStudentSummary(SsStudent s) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", s.getId()); m.put("studentName", s.getStudentName());
        m.put("memberRef", s.getMemberRef()); m.put("familyMemberId", s.getFamilyMemberId());
        m.put("contactEmail", s.getContactEmail()); m.put("clientId", s.getClientId());
        m.put("classId", s.getClassId()); m.put("deleteFlag", s.isDeleteFlag());
        return m;
    }

    /**
     * ONE-TIME DATA FIX — remove after use.
     * POST /api/admin/ss/fix-enroll-christina
     * Inserts Christina Thomas's missing ss_student row for CGP-00001.
     * Idempotent: if a row already exists it does nothing.
     */
    @PostMapping("/api/admin/ss/fix-enroll-christina")
    @Transactional
    public ResponseEntity<?> fixEnrollChristina(HttpServletRequest req) {
        final String CLIENT_ID  = "CGP-00001";
        final String MEMBER_REF = "MBRdf511aa2-11c3-4623-9ab9-b298c8d941d3";
        final Integer FM_ID     = 147;
        final String EMAIL      = "meetchriz@gmail.com";
        final String NAME       = "Christina Thomas";

        // Idempotency check
        boolean exists = studentRepo.findByMemberRefAndClientIdAndDeleteFlagFalse(MEMBER_REF, CLIENT_ID).isPresent()
                || studentRepo.findByFamilyMemberIdAndClientIdAndDeleteFlagFalse(FM_ID, CLIENT_ID).isPresent();
        if (exists) return ResponseEntity.ok(Map.of("status", "already_enrolled"));

        // Find a class for this org
        List<SsClass> classes = classRepo.findByClientIdAndDeleteFlagFalse(CLIENT_ID);
        if (classes.isEmpty()) return ResponseEntity.status(400).body(Map.of("error", "No classes found for " + CLIENT_ID));
        classes.sort(Comparator.comparingLong(SsClass::getId));
        SsClass cls = classes.get(0); // use first available class (lowest id)

        // Find a teacher for this org
        List<SsTeacher> teachers = teacherRepo.findByClientIdAndDeleteFlagFalse(CLIENT_ID);
        if (teachers.isEmpty()) return ResponseEntity.status(400).body(Map.of("error", "No teachers found for " + CLIENT_ID));
        teachers.sort(Comparator.comparingLong(SsTeacher::getId));
        SsTeacher teacher = teachers.get(0);

        SsStudent student = new SsStudent();
        student.setClientId(CLIENT_ID);
        student.setMemberRef(MEMBER_REF);
        student.setFamilyMemberId(FM_ID);
        student.setStudentName(NAME);
        student.setContactEmail(EMAIL);
        student.setClassId(cls.getId());
        student.setTeacherId(teacher.getId());
        student.setDeleteFlag(false);
        studentRepo.save(student);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", "enrolled");
        result.put("studentId", student.getId());
        result.put("classId", cls.getId());
        result.put("className", cls.getClassName());
        result.put("teacherId", teacher.getId());
        result.put("teacherName", teacher.getTeacherName());
        return ResponseEntity.ok(result);
    }

    /** Returns the student's classes, exams, and submissions. */
    @GetMapping("/api/member/ss/dashboard")
    public ResponseEntity<?> memberDashboard(HttpServletRequest req) {
        HttpSession session = req.getSession(false);
        if (session == null) return ResponseEntity.status(401).build();
        if (resolveSsIdentity(session) == null) return ResponseEntity.ok(Map.of("enrolled", false));

        SsStudent student = resolveStudent(session);
        if (student == null) return ResponseEntity.ok(Map.of("enrolled", false));

        SsClass cls = classRepo.findById(student.getClassId()).orElse(null);
        List<SsExam> exams = examRepo.findByClassIdAndDeleteFlagFalseOrderByIdDesc(student.getClassId())
                .stream().filter(e -> "Published".equals(e.getStatus())).collect(Collectors.toList());

        List<Map<String,Object>> examList = exams.stream().map(e -> {
            Map<String,Object> m = new LinkedHashMap<>();
            m.put("id", e.getId());
            m.put("examTitle", e.getExamTitle());
            m.put("totalQuestions", e.getTotalQuestions());
            m.put("requiredQuestions", e.getRequiredQuestions());
            m.put("durationMinutes", e.getDurationMinutes());
            SsSubmission sub = submissionRepo.findByExamIdAndStudentIdAndDeleteFlagFalse(e.getId(), student.getId()).orElse(null);
            if (sub != null) {
                m.put("submissionStatus", sub.getStatus());
                m.put("submissionId", sub.getId());
                m.put("autoMarks", sub.getAutoMarks());
                m.put("manualMarks", sub.getManualMarks());
                m.put("totalMarks", sub.getTotalMarks());
            } else {
                m.put("submissionStatus", null);
            }
            return m;
        }).collect(Collectors.toList());

        List<SsLesson> lessons = lessonRepo.findByStudentIdAndDeleteFlagFalseOrderBySortOrderAsc(student.getId());

        Map<String,Object> result = new LinkedHashMap<>();
        result.put("enrolled", true);
        result.put("studentId", student.getId());
        result.put("studentName", student.getStudentName());
        result.put("className", cls != null ? cls.getClassName() : "");
        result.put("exams", examList);
        result.put("lessons", lessons);
        return ResponseEntity.ok(result);
    }

    /** Start or resume an exam for the logged-in student. Returns questions. */
    @Transactional
    @PostMapping("/api/member/ss/exams/{examId}/start")
    public ResponseEntity<?> startExam(@PathVariable Long examId, HttpServletRequest req) {
        HttpSession session = req.getSession(false);
        if (session == null) return ResponseEntity.status(401).build();
        String[] ssId = resolveSsIdentity(session);
        if (ssId == null) return ResponseEntity.ok(Map.of("error", "Not a member account"));
        String clientId = ssId[1];

        SsStudent student = resolveStudent(session);
        if (student == null) return ResponseEntity.status(404).body(Map.of("error", "Not enrolled"));

        SsExam exam = examRepo.findById(examId).orElse(null);
        if (exam == null || !"Published".equals(exam.getStatus()))
            return ResponseEntity.status(404).body(Map.of("error", "Exam not available"));

        SsSubmission sub = submissionRepo.findByExamIdAndStudentIdAndDeleteFlagFalse(examId, student.getId()).orElse(null);
        if (sub != null && "Submitted".equals(sub.getStatus()))
            return ResponseEntity.status(400).body(Map.of("error", "Already submitted"));

        if (sub == null) {
            // After a republish the old submission is soft-deleted, but the unique constraint on
            // (exam_id, student_id) still blocks a new INSERT. Use @Modifying JPQL bulk deletes —
            // these fire immediate SQL DELETEs rather than deferred Hibernate actions, so the
            // constraint is cleared in the same transaction before the INSERT is attempted.
            answerRepo.deleteByExamIdAndStudentId(examId, student.getId());   // child rows first
            submissionRepo.deleteByExamIdAndStudentId(examId, student.getId()); // then parent row

            sub = new SsSubmission();
            sub.setClientId(clientId);
            sub.setExamId(examId);
            sub.setStudentId(student.getId());
            sub.setStartedAt(LocalDateTime.now());
            sub.setStatus("InProgress");
            sub = submissionRepo.save(sub);
        }

        List<SsQuestion> questions = questionRepo.findByExamIdAndDeleteFlagFalseOrderBySortOrderAsc(examId);
        List<SsAnswer> answers = answerRepo.findBySubmissionId(sub.getId());
        Map<Long,String> answerMap = new java.util.HashMap<>();
        for (SsAnswer ans1 : answers) { answerMap.put(ans1.getQuestionId(), ans1.getAnswerText() != null ? ans1.getAnswerText() : ""); }

        // Strip correct answers before sending to student
        List<Map<String,Object>> qList = questions.stream().map(q -> {
            Map<String,Object> m = new LinkedHashMap<>();
            m.put("id", q.getId());
            m.put("questionType", q.getQuestionType());
            m.put("questionText", q.getQuestionText());
            m.put("optionsJson", q.getOptionsJson());
            m.put("marks", q.getMarks());
            m.put("sortOrder", q.getSortOrder());
            m.put("savedAnswer", answerMap.getOrDefault(q.getId(), ""));
            return m;
        }).collect(Collectors.toList());

        Map<String,Object> result = new LinkedHashMap<>();
        result.put("submissionId", sub.getId());
        result.put("startedAtMs", sub.getStartedAt() != null
                ? sub.getStartedAt().atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
                : System.currentTimeMillis());
        result.put("durationMinutes", exam.getDurationMinutes());
        result.put("extraTimeMinutes", sub.getExtraTimeMinutes());
        result.put("requiredQuestions", exam.getRequiredQuestions());
        result.put("questions", qList);
        return ResponseEntity.ok(result);
    }

    /** Save a single answer during exam (called on next/back). */
    @PostMapping("/api/member/ss/submissions/{submissionId}/answer")
    public ResponseEntity<?> saveAnswer(@PathVariable Long submissionId,
                                        @RequestBody Map<String,Object> body,
                                        HttpServletRequest req) {
        HttpSession session = req.getSession(false);
        if (session == null) return ResponseEntity.status(401).build();
        if (resolveSsIdentity(session) == null) return ResponseEntity.ok(Map.of("error", "Not a member account"));

        SsSubmission sub = submissionRepo.findById(submissionId).orElse(null);
        if (sub == null || "Submitted".equals(sub.getStatus()))
            return ResponseEntity.status(400).body(Map.of("error", "Cannot modify"));

        Long questionId = Long.parseLong(str(body.get("questionId")));
        String answerText = str(body.get("answerText"));
        String clientId = sub.getClientId();

        SsAnswer a = answerRepo.findBySubmissionIdAndQuestionId(submissionId, questionId)
                .orElseGet(() -> { SsAnswer na = new SsAnswer(); na.setClientId(clientId);
                    na.setSubmissionId(submissionId); na.setQuestionId(questionId); return na; });
        a.setAnswerText(answerText);
        answerRepo.save(a);
        return ResponseEntity.ok(Map.of("status", "saved"));
    }

    /** Student: poll for extra time granted by teacher/admin. */
    @GetMapping("/api/member/ss/submissions/{submissionId}/extra-time")
    public ResponseEntity<?> pollExtraTime(@PathVariable Long submissionId, HttpServletRequest req) {
        HttpSession session = req.getSession(false);
        if (session == null) return ResponseEntity.status(401).build();
        if (resolveSsIdentity(session) == null) return ResponseEntity.ok(Map.of("extraTimeMinutes", 0));
        SsSubmission sub = submissionRepo.findById(submissionId).orElse(null);
        if (sub == null) return ResponseEntity.status(404).build();
        return ResponseEntity.ok(Map.of("extraTimeMinutes", sub.getExtraTimeMinutes()));
    }

    /** Submit the exam — auto-grade, email teacher. */
    @PostMapping("/api/member/ss/submissions/{submissionId}/submit")
    @Transactional
    public ResponseEntity<?> submitExam(@PathVariable Long submissionId, HttpServletRequest req) {
        HttpSession session = req.getSession(false);
        if (session == null) return ResponseEntity.status(401).build();
        if (resolveSsIdentity(session) == null) return ResponseEntity.ok(Map.of("error", "Not a member account"));

        SsSubmission sub = submissionRepo.findById(submissionId).orElse(null);
        if (sub == null) return ResponseEntity.status(404).build();
        if ("Submitted".equals(sub.getStatus()))
            return ResponseEntity.status(400).body(Map.of("error", "Already submitted"));

        // Auto-grade
        List<SsQuestion> questions = questionRepo.findByExamIdAndDeleteFlagFalseOrderBySortOrderAsc(sub.getExamId());
        List<SsAnswer> answers = answerRepo.findBySubmissionId(submissionId);
        // Use HashMap loop instead of Collectors.toMap() to avoid duplicate-key exception
        Map<Long,SsAnswer> ansMap = new java.util.HashMap<>();
        for (SsAnswer a : answers) { ansMap.put(a.getQuestionId(), a); }

        int autoMarks = 0;
        for (SsQuestion q : questions) {
            SsAnswer a = ansMap.get(q.getId());
            if (a == null) continue;
            String qType = q.getQuestionType() != null ? q.getQuestionType() : "";
            String studentAns = a.getAnswerText() != null ? a.getAnswerText().trim() : "";
            String correctAns = q.getCorrectAnswer() != null ? q.getCorrectAnswer().trim() : "";
            int maxMarks = q.getMarks() != null ? q.getMarks() : 1;

            if (List.of("MCQ","TrueFalse").contains(qType)) {
                // ── Exact match (case-insensitive) ──────────────────────────────
                if (!correctAns.isBlank()) {
                    boolean correct = correctAns.equalsIgnoreCase(studentAns);
                    a.setIsCorrect(correct);
                    if (correct) autoMarks += maxMarks;
                }

            } else {
                // ── Unified fuzzy / keyword grading for all other question types ─
                // (FillBlank, ShortAnswer, LongAnswer, Comprehensive, Essay, etc.)
                // AnswerGrader.grade() picks the right path automatically:
                //   • correct answer ≤ 5 words  → Levenshtein fuzzy match
                //   • correct answer > 5 words  → keyword / phrase coverage
                //     ≥ 60 % coverage → auto-correct (full marks)
                //     ≥ 30 % coverage → suggested partial marks, teacher reviews
                //     < 30 % coverage → suggested 0, teacher reviews
                if (!correctAns.isBlank() && !studentAns.isBlank()) {
                    AnswerGrader.GradeResult gr = AnswerGrader.grade(studentAns, correctAns, maxMarks);
                    a.setIsCorrect(gr.isCorrect);
                    a.setSuggestedMarks(gr.suggestedMarks);
                    if (Boolean.TRUE.equals(gr.isCorrect) && gr.autoGraded) {
                        autoMarks += maxMarks;
                    }
                    if (!gr.matchedKeywords.isEmpty()) {
                        StringBuilder kwJson = new StringBuilder("[");
                        for (int ki = 0; ki < gr.matchedKeywords.size(); ki++) {
                            if (ki > 0) kwJson.append(",");
                            kwJson.append("\"").append(gr.matchedKeywords.get(ki)
                                    .replace("\\","\\\\").replace("\"","\\\"")).append("\"");
                        }
                        kwJson.append("]");
                        a.setMatchHighlights(kwJson.toString());
                    }
                }
            }
            answerRepo.save(a);
        }

        sub.setStatus("Submitted");
        sub.setSubmittedAt(LocalDateTime.now());
        sub.setAutoMarks(autoMarks);
        sub.setTotalMarks(autoMarks + (sub.getManualMarks() != null ? sub.getManualMarks() : 0));
        submissionRepo.save(sub);

        // Email teacher
        SsStudent student = studentRepo.findById(sub.getStudentId()).orElse(null);
        SsExam exam       = examRepo.findById(sub.getExamId()).orElse(null);
        int requiredQ = (exam != null && exam.getRequiredQuestions() != null && exam.getRequiredQuestions() > 0)
                ? exam.getRequiredQuestions() : questions.size();
        if (student != null && exam != null) {
            String emailHtml = buildSubmissionEmail(student, exam, questions, ansMap, autoMarks);
            String subject   = "📋 Exam Submitted: " + exam.getExamTitle() + " — " + student.getStudentName();

            // Email teacher
            SsTeacher teacher = teacherRepo.findById(student.getTeacherId()).orElse(null);
            if (teacher != null && teacher.getEmail() != null && !teacher.getEmail().isBlank()) {
                try {
                    emailService.sendOrgEmail(teacher.getEmail(), subject, emailHtml, sub.getClientId());
                } catch (Exception e) {
                    log.error("Failed to send submission email to teacher", e);
                }
            }

            // Email Head of Household copy (if exam has copyToHoh enabled)
            if (exam.isCopyToHoh() && student.getFamilyMemberId() != null) {
                try {
                    String hohEmail = familyMemberRepo.findHohEmailByFamilyMemberId(student.getFamilyMemberId());
                    if (hohEmail != null && !hohEmail.isBlank()) {
                        String hohHtml = buildSubmissionEmail(student, exam, questions, ansMap, autoMarks);
                        emailService.sendOrgEmail(hohEmail, subject, hohHtml, sub.getClientId());
                    }
                } catch (Exception e) {
                    log.error("Failed to send HoH copy email for submission", e);
                }
            }
        }

        return ResponseEntity.ok(Map.of("status", "submitted", "autoMarks", autoMarks, "requiredQuestions", requiredQ));
    }

    /**
     * Returns a student's results for a submitted exam.
     * Includes per-question: questionText, type, options, studentAnswer, isCorrect, correctAnswer.
     * correctAnswer is only exposed after submission.
     *
     * Uses ALL questions (including soft-deleted) so answers recorded before a re-upload
     * are still matched and shown — fixing the "No answer provided" issue for Q10.
     * Score denominator = requiredQuestions (not totalQuestions).
     */
    @GetMapping("/api/member/ss/submissions/{submissionId}/results")
    public ResponseEntity<?> getStudentResults(@PathVariable Long submissionId, HttpServletRequest req) {
        HttpSession session = req.getSession(false);
        if (session == null) return ResponseEntity.status(401).build();
        String[] ssId = resolveSsIdentity(session);
        if (ssId == null) return ResponseEntity.ok(Map.of("error", "Not a member account"));
        String clientId  = ssId[1];

        SsSubmission sub = submissionRepo.findById(submissionId).orElse(null);
        if (sub == null || !sub.getClientId().equals(clientId))
            return ResponseEntity.status(404).build();
        if (!"Submitted".equals(sub.getStatus()) && !"TimedOut".equals(sub.getStatus()) && !"Graded".equals(sub.getStatus()))
            return ResponseEntity.status(400).body(Map.of("error", "Not yet submitted"));

        // Verify the submission belongs to this student (use resilient resolveStudent)
        SsStudent student = resolveStudent(session);
        if (student == null || !student.getId().equals(sub.getStudentId()))
            return ResponseEntity.status(403).build();

        SsExam exam = examRepo.findById(sub.getExamId()).orElse(null);

        // Fetch ALL questions (including soft-deleted) so answers from before a re-upload are matched
        List<SsQuestion> allQuestions = questionRepo.findByExamIdOrderBySortOrderAsc(sub.getExamId());
        List<SsAnswer> answers = answerRepo.findBySubmissionId(submissionId);
        Map<Long, SsAnswer> ansMap = new java.util.HashMap<>();
        for (SsAnswer a : answers) { ansMap.put(a.getQuestionId(), a); }

        // Only include questions that have a student answer (handles re-upload: old IDs have answers,
        // new IDs don't — we show the old ones to the student so nothing is "missing")
        Set<Long> answeredQuestionIds = answers.stream()
                .filter(a -> a.getAnswerText() != null && !a.getAnswerText().isBlank())
                .map(SsAnswer::getQuestionId).collect(Collectors.toSet());

        // Active (non-deleted) questions first; supplement with deleted ones that have answers
        List<SsQuestion> activeQuestions = allQuestions.stream()
                .filter(q -> !q.isDeleteFlag()).collect(Collectors.toList());
        Set<Long> activeIds = activeQuestions.stream().map(SsQuestion::getId).collect(Collectors.toSet());
        List<SsQuestion> orphanedAnswered = allQuestions.stream()
                .filter(q -> q.isDeleteFlag() && answeredQuestionIds.contains(q.getId())
                          && !activeIds.contains(q.getId()))
                .collect(Collectors.toList());

        // Combine: active questions + any orphaned (deleted) questions the student answered
        List<SsQuestion> displayQuestions = new ArrayList<>(activeQuestions);
        displayQuestions.addAll(orphanedAnswered);
        displayQuestions.sort(Comparator.comparingInt(q -> q.getSortOrder() != null ? q.getSortOrder() : 9999));

        List<Map<String,Object>> items = displayQuestions.stream().map(q -> {
            Map<String,Object> r = new LinkedHashMap<>();
            r.put("questionId", q.getId());
            r.put("questionText", q.getQuestionText());
            r.put("questionType", q.getQuestionType());
            r.put("optionsJson", q.getOptionsJson());
            r.put("marks", q.getMarks());
            r.put("sortOrder", q.getSortOrder());
            SsAnswer a = ansMap.get(q.getId());
            r.put("studentAnswer", a != null ? a.getAnswerText() : null);
            // Re-grade on the fly using current correctAnswer (handles admin edits after submission)
            boolean autoGradable = List.of("MCQ","TrueFalse","FillBlank").contains(q.getQuestionType());
            Boolean isCorrect = null;
            if (a != null && autoGradable && q.getCorrectAnswer() != null && !q.getCorrectAnswer().isBlank()
                    && a.getAnswerText() != null && !a.getAnswerText().isBlank()) {
                if ("FillBlank".equals(q.getQuestionType())) {
                    // Typed answers get the same spelling tolerance as submitExam's
                    // AnswerGrader (e.g. "Cheriath"/"Charith" → "Cherith",
                    // "Jehova" → "Jehovah") — a strict comparison here would show
                    // fuzzily-accepted answers as wrong in the student's results.
                    isCorrect = q.getCorrectAnswer().trim().equalsIgnoreCase(a.getAnswerText().trim())
                            || AnswerGrader.fuzzyMatchShort(a.getAnswerText(), q.getCorrectAnswer());
                } else {
                    // MCQ / TrueFalse — options are clicked, exact match is right
                    isCorrect = q.getCorrectAnswer().trim().equalsIgnoreCase(a.getAnswerText().trim());
                }
            } else if (a != null) {
                isCorrect = a.getIsCorrect(); // fall back to stored value if no correctAnswer set
            }
            r.put("isCorrect", isCorrect);
            r.put("correctAnswer", autoGradable ? q.getCorrectAnswer() : null);
            r.put("manualMarks", a != null ? a.getManualMarks() : null);
            return r;
        }).collect(Collectors.toList());

        // Score denominator = requiredQuestions (not total); fall back to answered count
        int requiredQ = (exam != null && exam.getRequiredQuestions() != null && exam.getRequiredQuestions() > 0)
                ? exam.getRequiredQuestions()
                : (int) items.stream().filter(m -> m.get("studentAnswer") != null
                        && !((String)m.get("studentAnswer")).isBlank()).count();

        Map<String,Object> result = new LinkedHashMap<>();
        result.put("submissionId", submissionId);
        result.put("autoMarks",   sub.getAutoMarks());
        result.put("manualMarks", sub.getManualMarks());
        result.put("totalMarks",  sub.getTotalMarks());
        result.put("reviewed",    sub.isReviewed());
        result.put("status",      sub.getStatus());
        result.put("requiredQuestions", requiredQ);
        result.put("questions", items);
        return ResponseEntity.ok(result);
    }

    // =========================================================================
    // MEMBER: HEAD-OF-HOUSEHOLD RESULTS VIEW
    // =========================================================================

    /**
     * Returns submitted exam results for all children in the logged-in member's family.
     * Only accessible when the logged-in member is Head of Household (or Spouse / equivalent).
     * Response: list of { studentName, examTitle, className, submittedAt,
     *                     autoMarks, totalMarks, submissionId }
     */
    @GetMapping("/api/member/ss/family/results")
    public ResponseEntity<?> familyResults(HttpServletRequest req) {
        HttpSession session = req.getSession(false);
        if (session == null) return ResponseEntity.status(401).build();
        if (resolveSsIdentity(session) == null) return ResponseEntity.ok(List.of());

        Object memberIdObj = session.getAttribute("memberId");
        if (memberIdObj == null) return ResponseEntity.ok(List.of());
        Integer memberId;
        try { memberId = Integer.parseInt(String.valueOf(memberIdObj)); }
        catch (NumberFormatException ex) { return ResponseEntity.ok(List.of()); }

        String clientId = (String) session.getAttribute("appClientId");
        if (clientId == null) clientId = (String) session.getAttribute("clientId");

        // Find all active students in the same family whose familyMemberId is in this family
        // Step 1: get the caller's family ID from the family_member row
        FamilyMember self = familyMemberRepo.findById(memberId).orElse(null);
        if (self == null || self.getFamily() == null) return ResponseEntity.ok(List.of());
        Integer familyId = self.getFamily().getId();

        // Step 2: find all family_member IDs belonging to this family
        List<FamilyMember> familyMembers = familyMemberRepo.findActiveMembersByMemberId(memberId);
        List<Integer> familyMemberIds = familyMembers.stream()
                .map(FamilyMember::getId).collect(java.util.stream.Collectors.toList());
        if (familyMemberIds.isEmpty()) return ResponseEntity.ok(List.of());

        // Step 3: find ss_student rows whose familyMemberId is in that set
        final String cid = clientId;
        List<SsStudent> children = studentRepo.findAll().stream()
                .filter(s -> !s.isDeleteFlag()
                        && s.getFamilyMemberId() != null
                        && familyMemberIds.contains(s.getFamilyMemberId())
                        && (cid == null || cid.equals(s.getClientId())))
                .collect(java.util.stream.Collectors.toList());

        if (children.isEmpty()) return ResponseEntity.ok(List.of());

        List<Map<String,Object>> out = new ArrayList<>();
        for (SsStudent child : children) {
            List<SsSubmission> subs = submissionRepo.findByStudentIdAndDeleteFlagFalse(child.getId());
            for (SsSubmission sub : subs) {
                if (!"Submitted".equals(sub.getStatus()) && !"TimedOut".equals(sub.getStatus())) continue;
                SsExam exam = examRepo.findById(sub.getExamId()).orElse(null);
                SsClass cls = exam != null ? classRepo.findById(exam.getClassId()).orElse(null) : null;
                Map<String,Object> r = new LinkedHashMap<>();
                r.put("submissionId",  sub.getId());
                r.put("studentName",   child.getStudentName());
                r.put("examTitle",     exam != null ? exam.getExamTitle() : "");
                r.put("className",     cls  != null ? cls.getClassName()  : "");
                r.put("submittedAt",   sub.getSubmittedAt());
                r.put("autoMarks",     sub.getAutoMarks());
                r.put("totalMarks",    sub.getTotalMarks());
                r.put("examId",        sub.getExamId());
                out.add(r);
            }
        }
        // Sort by submittedAt desc
        out.sort((a, b) -> {
            if (a.get("submittedAt") == null) return 1;
            if (b.get("submittedAt") == null) return -1;
            return ((java.time.LocalDateTime)b.get("submittedAt"))
                    .compareTo((java.time.LocalDateTime)a.get("submittedAt"));
        });
        return ResponseEntity.ok(out);
    }

    /**
     * Returns enrolled students that the logged-in member is the parent/guardian of.
     *
     * <p>Scope: only students whose {@code ss_student.family_member_id} equals the
     * logged-in member's own {@code memberId}.  This intentionally does NOT fan out to
     * the whole family group — doing so would expose sibling or co-household members'
     * children to unrelated adults who happen to share the same Family record.
     *
     * <p>Fallback: if no students are linked by familyMemberId, a secondary check is
     * made by contactEmail, but only for student rows that have NO familyMemberId set
     * (i.e. pre-signup enrollments where the admin typed the parent's email manually).
     * This prevents the email match from pulling in students already linked to a
     * different family member.
     *
     * <p>Response: list of { studentId, studentName, className, teacherName, exams }
     */
    @GetMapping("/api/member/ss/family/enrolled")
    public ResponseEntity<?> familyEnrolled(HttpServletRequest req) {
        HttpSession session = req.getSession(false);
        if (session == null) return ResponseEntity.status(401).build();
        if (resolveSsIdentity(session) == null) return ResponseEntity.ok(List.of());

        Object memberIdObj = session.getAttribute("memberId");
        if (memberIdObj == null) return ResponseEntity.ok(List.of());
        Integer memberId;
        try { memberId = Integer.parseInt(String.valueOf(memberIdObj)); }
        catch (NumberFormatException ex) { return ResponseEntity.ok(List.of()); }

        String clientId = (String) session.getAttribute("appClientId");
        if (clientId == null) clientId = (String) session.getAttribute("clientId");

        FamilyMember self = familyMemberRepo.findById(memberId).orElse(null);
        if (self == null) return ResponseEntity.ok(List.of());

        // Primary: students whose familyMemberId points directly to THIS logged-in member.
        // This is only true for children that the logged-in member specifically enrolled.
        final String cid = clientId;
        List<SsStudent> children = studentRepo.findAll().stream()
                .filter(s -> !s.isDeleteFlag()
                        && memberId.equals(s.getFamilyMemberId())
                        && (cid == null || cid.toUpperCase().startsWith("MBR") || cid.equals(s.getClientId())))
                .collect(java.util.stream.Collectors.toList());

        // Fallback: contactEmail match, but ONLY for students with no familyMemberId set
        // (pre-signup/manual enrollments where admin typed the parent's email).
        // Scoped to unlinked rows only — linked rows are already covered above and
        // must NOT be re-matched here (doing so would pull in other members' children
        // who happen to share the same contact email).
        if (children.isEmpty()) {
            String selfEmail = self.getEmail();
            if (selfEmail != null && !selfEmail.isBlank()) {
                final String emailLc = selfEmail.trim().toLowerCase();
                children = studentRepo.findAll().stream()
                        .filter(s -> !s.isDeleteFlag()
                                && s.getFamilyMemberId() == null          // unlinked rows only
                                && s.getContactEmail() != null
                                && s.getContactEmail().trim().toLowerCase().equals(emailLc)
                                && (cid == null || cid.toUpperCase().startsWith("MBR") || cid.equals(s.getClientId())))
                        .collect(java.util.stream.Collectors.toList());
            }
        }

        if (children.isEmpty()) return ResponseEntity.ok(List.of());

        List<Map<String,Object>> out = new ArrayList<>();
        for (SsStudent child : children) {
            SsClass cls = classRepo.findById(child.getClassId()).orElse(null);
            SsTeacher teacher = teacherRepo.findById(child.getTeacherId()).orElse(null);
            List<SsExam> exams = cls != null
                    ? examRepo.findByClassIdAndDeleteFlagFalseOrderByIdDesc(cls.getId()).stream()
                              .filter(e -> "Published".equals(e.getStatus())).collect(java.util.stream.Collectors.toList())
                    : List.of();
            List<SsSubmission> subs = submissionRepo.findByStudentIdAndDeleteFlagFalse(child.getId());
            Map<Long,SsSubmission> subByExam = new java.util.HashMap<>();
            for (SsSubmission sub : subs) subByExam.put(sub.getExamId(), sub);

            List<Map<String,Object>> examList = exams.stream().map(e -> {
                Map<String,Object> m = new LinkedHashMap<>();
                m.put("id", e.getId());
                m.put("examTitle", e.getExamTitle());
                m.put("totalQuestions", e.getTotalQuestions());
                m.put("durationMinutes", e.getDurationMinutes());
                SsSubmission sub = subByExam.get(e.getId());
                m.put("submissionStatus", sub != null ? sub.getStatus() : null);
                m.put("submissionId",    sub != null ? sub.getId() : null);
                m.put("autoMarks",       sub != null ? sub.getAutoMarks() : null);
                m.put("totalMarks",      sub != null ? sub.getTotalMarks() : null);
                return m;
            }).collect(java.util.stream.Collectors.toList());

            Map<String,Object> r = new LinkedHashMap<>();
            r.put("studentId",   child.getId());
            r.put("studentName", child.getStudentName());
            r.put("className",   cls     != null ? cls.getClassName()       : "");
            r.put("teacherName", teacher != null ? teacher.getTeacherName() : "");
            r.put("exams",       examList);
            out.add(r);
        }
        return ResponseEntity.ok(out);
    }

    /**
     * Returns the full answer sheet for a submission, accessible by the HoH of the child's family.
     * Same data as the teacher view — questions, answers, correctness, marks.
     */
    @GetMapping("/api/member/ss/family/submissions/{submissionId}/answers")
    public ResponseEntity<?> familySubmissionAnswers(@PathVariable Long submissionId, HttpServletRequest req) {
        HttpSession session = req.getSession(false);
        if (session == null) return ResponseEntity.status(401).build();
        if (resolveSsIdentity(session) == null) return ResponseEntity.ok(List.of());

        Object memberIdObj = session.getAttribute("memberId");
        if (memberIdObj == null) return ResponseEntity.status(403).build();
        Integer memberId;
        try { memberId = Integer.parseInt(String.valueOf(memberIdObj)); }
        catch (NumberFormatException ex) { return ResponseEntity.status(403).build(); }

        SsSubmission sub = submissionRepo.findById(submissionId).orElse(null);
        if (sub == null) return ResponseEntity.status(404).build();

        // Verify this submission's student is in the caller's family
        SsStudent student = studentRepo.findById(sub.getStudentId()).orElse(null);
        if (student == null) return ResponseEntity.status(404).build();
        if (student.getFamilyMemberId() == null) return ResponseEntity.status(403).build();

        FamilyMember self = familyMemberRepo.findById(memberId).orElse(null);
        if (self == null || self.getFamily() == null) return ResponseEntity.status(403).build();

        List<FamilyMember> familyMembers = familyMemberRepo.findActiveMembersByMemberId(memberId);
        boolean inFamily = familyMembers.stream()
                .anyMatch(m -> m.getId().equals(student.getFamilyMemberId()));
        if (!inFamily) return ResponseEntity.status(403).build();

        SsExam exam = examRepo.findById(sub.getExamId()).orElse(null);
        List<SsQuestion> questions = questionRepo.findByExamIdOrderBySortOrderAsc(sub.getExamId());
        List<SsAnswer> answers = answerRepo.findBySubmissionId(submissionId);
        Map<Long,SsAnswer> ansMap = new java.util.HashMap<>();
        for (SsAnswer a : answers) { ansMap.put(a.getQuestionId(), a); }

        List<Map<String,Object>> items = questions.stream()
                .filter(q -> !q.isDeleteFlag())
                .map(q -> {
                    Map<String,Object> r = new LinkedHashMap<>();
                    r.put("questionId",   q.getId());
                    r.put("questionText", q.getQuestionText());
                    r.put("questionType", q.getQuestionType());
                    r.put("optionsJson",  q.getOptionsJson());
                    r.put("correctAnswer", q.getCorrectAnswer());
                    r.put("marks",        q.getMarks());
                    r.put("sortOrder",    q.getSortOrder());
                    SsAnswer a = ansMap.get(q.getId());
                    r.put("answerId",    a != null ? a.getId() : null);
                    r.put("answerText",  a != null ? a.getAnswerText() : null);
                    r.put("isCorrect",   a != null ? a.getIsCorrect()  : null);
                    r.put("manualMarks", a != null ? a.getManualMarks(): null);
                    return r;
                }).collect(java.util.stream.Collectors.toList());

        Map<String,Object> resp = new LinkedHashMap<>();
        resp.put("submissionId", submissionId);
        resp.put("studentName",  student.getStudentName());
        resp.put("examTitle",    exam != null ? exam.getExamTitle() : "");
        resp.put("autoMarks",    sub.getAutoMarks()   != null ? sub.getAutoMarks()   : 0);
        resp.put("manualMarks",  sub.getManualMarks() != null ? sub.getManualMarks() : 0);
        resp.put("totalMarks",   sub.getTotalMarks()  != null ? sub.getTotalMarks()  : 0);
        resp.put("questions",    items);
        return ResponseEntity.ok(resp);
    }

    // =========================================================================
    // HELPERS
    // =========================================================================

    private void recalcTotal(Long submissionId) {
        submissionRepo.findById(submissionId).ifPresent(sub -> {
            List<SsAnswer> answers = answerRepo.findBySubmissionId(submissionId);
            int manualTotal = answers.stream()
                    .mapToInt(a -> a.getManualMarks() != null ? a.getManualMarks() : 0).sum();
            sub.setManualMarks(manualTotal);
            sub.setTotalMarks((sub.getAutoMarks() != null ? sub.getAutoMarks() : 0) + manualTotal);
            submissionRepo.save(sub);
        });
    }

    private String extractText(MultipartFile file) throws IOException {
        String name = file.getOriginalFilename() != null ? file.getOriginalFilename().toLowerCase() : "";
        if (name.endsWith(".pdf")) {
            try (PDDocument doc = Loader.loadPDF(file.getBytes())) {
                return new PDFTextStripper().getText(doc);
            }
        } else if (name.endsWith(".docx")) {
            try (XWPFDocument doc = new XWPFDocument(file.getInputStream())) {
                // Join with single newline — parseQuestions handles grouping
                // of question + options regardless of whether each option is
                // its own paragraph (Word default) or on the same line.
                return doc.getParagraphs().stream()
                        .map(XWPFParagraph::getText)
                        .filter(t -> t != null && !t.isBlank())
                        .collect(Collectors.joining("\n"));
            }
        }
        return new String(file.getBytes());
    }

    /**
     * Question parser that handles both formats:
     *  - Options on the same line/paragraph as the question (plain-text / PDF)
     *  - Options each on their own line (Word .docx, where every paragraph is a line)
     *
     * Algorithm: scan lines one by one, grouping option lines that immediately
     * follow a question line. An "Answers" / "Answer Key" heading triggers
     * collection of the answer key for auto-grading.
     */
    /**
     * Parses a question paper that may use either of two formats:
     *
     * <p><b>Format A – Positional (unnumbered), used by "Test Ch 1-13.docx" and similar:</b>
     * <pre>
     *   What is Babylon known for in the Bible?      ← short-answer q1
     *   Who built the city of Babel?                 ← short-answer q2
     *   ...                                          ← up to 50 short-answer questions
     *   Comprehensive questions                      ← section header (ignored)
     *   Dream of Nebuchadnezzar                      ← comprehensive q1
     *   How did the Roman Empire help …              ← comprehensive q2
     *   ...                                          ← up to 5 comprehensive questions
     *   Answers                                      ← answer-key header
     *   An empire that stood against …               ← answer 1
     *   Nimrod                                       ← answer 2
     *   ...
     *   Comprehensive Answers                        ← comprehensive answer-key header
     *   A great statue which had …                   ← comprehensive answer 1
     *   ...
     * </pre>
     * Questions 1–50 are typed as {@code ShortAnswer}; questions 51–55 (the next 5
     * lines after the "Comprehensive questions" header) are typed as {@code Comprehensive}.
     * The "Write N Bible Verses" style prompt (no answer key entry) is treated as
     * Comprehensive with no answer.
     *
     * <p><b>Format B – Numbered with optional MCQ options:</b>
     * <pre>
     *   1. What is the capital of France?
     *   A. Berlin  B. London  C. Paris  D. Rome
     *   2. True or False: God is love.
     * </pre>
     *
     * The method auto-detects which format is in use: if the first non-blank
     * content line starts with a digit followed by a period/parenthesis/space it
     * is treated as Format B; otherwise as Format A.
     */
    private List<Map<String,Object>> parseQuestions(String text) {
        List<Map<String,Object>> result = new ArrayList<>();
        if (text == null || text.isBlank()) return result;

        // ── Normalise lines ───────────────────────────────────────────────────
        String[] rawLines = text.replace("\r\n", "\n").replace("\r", "\n").split("\n");
        List<String> lines = new ArrayList<>();

        // Regex patterns for inline-MCQ splitting (Format B)
        java.util.regex.Pattern optBoundary =
                java.util.regex.Pattern.compile("(?<=\\w)(?=[B-E]\\.)");
        java.util.regex.Pattern firstOpt =
                java.util.regex.Pattern.compile("(?<=\\W)(?=[A-E]\\.)");

        for (String l : rawLines) {
            String t = l.trim();
            if (t.isEmpty()) continue;
            // Detect inline MCQ: word char immediately before an A-E option letter+dot
            if (java.util.regex.Pattern.compile("(?<=\\w)[A-E]\\.").matcher(t).find()) {
                String[] parts = optBoundary.split(t);
                List<String> expanded = new ArrayList<>();
                for (int pi = 0; pi < parts.length; pi++) {
                    String chunk = parts[pi];
                    if (pi == 0) {
                        java.util.regex.Matcher fm = firstOpt.matcher(chunk);
                        if (fm.find() && fm.start() > 0) {
                            expanded.add(chunk.substring(0, fm.start()).trim());
                            expanded.add(chunk.substring(fm.start()).trim());
                        } else {
                            expanded.add(chunk.trim());
                        }
                    } else {
                        expanded.add(chunk.trim());
                    }
                }
                if (expanded.size() > 1) {
                    for (String e : expanded) { if (!e.isEmpty()) lines.add(e); }
                    continue;
                }
            }
            lines.add(t);
        }

        // ── Detect format ─────────────────────────────────────────────────────
        // "Positional" format: first content line does NOT look like "1." / "1)" / "1 "
        boolean positionalFormat = lines.stream()
                .filter(l -> !l.isBlank())
                .findFirst()
                .map(l -> !l.matches("^\\d+[.)\\s].*"))
                .orElse(false);

        if (positionalFormat) {
            return parsePositionalFormat(lines);
        }

        // ── Format B: numbered questions ──────────────────────────────────────
        // Collect short-answer answers (before "Comprehensive Answers") and
        // comprehensive answers (after "Comprehensive Answers").
        List<String> shortAnswLines = new ArrayList<>();
        List<String> compAnswLines  = new ArrayList<>();
        int answerSectionStart   = -1;
        int compAnswSectionStart = -1;

        for (int i = 0; i < lines.size(); i++) {
            String l = lines.get(i);
            if (l.equalsIgnoreCase("answers") || l.equalsIgnoreCase("answer key")
                    || l.equalsIgnoreCase("answer keys")) {
                answerSectionStart = i;
            } else if (l.equalsIgnoreCase("comprehensive answers")
                    || l.equalsIgnoreCase("comprehensive answer")) {
                compAnswSectionStart = i;
            }
        }

        if (answerSectionStart >= 0) {
            int shortEnd = compAnswSectionStart >= 0 ? compAnswSectionStart : lines.size();
            for (int i = answerSectionStart + 1; i < shortEnd; i++) {
                String l = lines.get(i).trim().replaceFirst("^\\d+[.)\\s]+", "").trim();
                if (!l.isEmpty()) shortAnswLines.add(l);
            }
        }
        if (compAnswSectionStart >= 0) {
            for (int i = compAnswSectionStart + 1; i < lines.size(); i++) {
                String l = lines.get(i).trim().replaceFirst("^\\d+[.)\\s]+", "").trim();
                if (!l.isEmpty()) compAnswLines.add(l);
            }
        }

        // Group numbered questions + options, stopping at answer section
        int limit = answerSectionStart >= 0 ? answerSectionStart : lines.size();

        List<String> currentOptions  = null;
        String       currentQuestion = null;
        boolean      currentIsComp   = false;
        List<Object[]> groups = new ArrayList<>(); // [questionText, options, isComprehensive]

        for (int i = 0; i < limit; i++) {
            String l = lines.get(i);
            boolean isCompHeader = l.equalsIgnoreCase("comprehensive questions")
                    || l.equalsIgnoreCase("comprehensive");
            if (isCompHeader) {
                if (currentQuestion != null) groups.add(new Object[]{currentQuestion, currentOptions, currentIsComp});
                currentQuestion = null; currentOptions = null;
                currentIsComp   = true;
                continue;
            }
            if (l.matches("^\\d+[.)\\s].*")) {
                if (currentQuestion != null) groups.add(new Object[]{currentQuestion, currentOptions, currentIsComp});
                currentQuestion = l.replaceFirst("^\\d+[.)\\s]+", "").trim();
                currentOptions  = new ArrayList<>();
            } else if (currentQuestion != null && l.matches("^[A-Ea-e][.)\\s].*")) {
                currentOptions.add(l.replaceFirst("^[A-Ea-e][.)\\s]+", "").trim());
            }
        }
        if (currentQuestion != null) groups.add(new Object[]{currentQuestion, currentOptions, currentIsComp});

        int shortIdx = 0, compIdx = 0;
        for (Object[] g : groups) {
            String     questionText = (String)           g[0];
            @SuppressWarnings("unchecked")
            List<String> options    = (List<String>)     g[1];
            boolean    isComp       = Boolean.TRUE.equals(g[2]);

            String type;
            String optionsJson = null;
            if (!options.isEmpty()) {
                type = "MCQ";
                StringBuilder sb = new StringBuilder("[");
                for (int i = 0; i < options.size(); i++) {
                    sb.append("\"").append(options.get(i).replace("\\","\\\\").replace("\"","\\\"")).append("\"");
                    if (i < options.size() - 1) sb.append(",");
                }
                sb.append("]");
                optionsJson = sb.toString();
            } else if (isComp) {
                type = "Comprehensive";
            } else if (questionText.toLowerCase().contains("true or false")
                    || questionText.toLowerCase().contains("true/false")) {
                type = "TrueFalse";
                optionsJson = "[\"True\",\"False\"]";
            } else if (questionText.contains("___") || questionText.toLowerCase().contains("fill in")) {
                type = "FillBlank";
            } else {
                type = "ShortAnswer";
            }

            String correctAnswer = null;
            if (isComp) {
                if (compIdx < compAnswLines.size()) correctAnswer = compAnswLines.get(compIdx++);
            } else {
                if (shortIdx < shortAnswLines.size()) {
                    String ans = shortAnswLines.get(shortIdx++).trim();
                    if (!ans.isEmpty()) {
                        if (type.equals("MCQ") && ans.matches("[A-Ea-e]")) {
                            int idx2 = ans.toUpperCase().charAt(0) - 'A';
                            correctAnswer = (idx2 >= 0 && idx2 < options.size()) ? options.get(idx2) : ans;
                        } else {
                            correctAnswer = ans;
                        }
                    }
                }
            }

            Map<String,Object> q = new LinkedHashMap<>();
            q.put("type", type);
            q.put("text", questionText);
            q.put("options", optionsJson);
            q.put("answer", correctAnswer);
            result.add(q);
        }
        return result;
    }

    /**
     * Positional-format parser.
     *
     * <p>Rules (matching "Test Ch 1-13.docx" and similar exam papers):
     * <ul>
     *   <li>Lines before "Comprehensive questions" header → {@code ShortAnswer} (max 50 taken)</li>
     *   <li>Lines after that header, before "Answers" → {@code Comprehensive} (max 5 taken)</li>
     *   <li>"Answers" section → short-answer key (one answer per line, 1-indexed)</li>
     *   <li>"Comprehensive Answers" section → comprehensive answer key (one answer per line)</li>
     * </ul>
     */
    private List<Map<String,Object>> parsePositionalFormat(List<String> lines) {
        // ── Locate section boundaries ─────────────────────────────────────────
        int compHeaderIdx    = -1;
        int ansHeaderIdx     = -1;
        int compAnsHeaderIdx = -1;

        for (int i = 0; i < lines.size(); i++) {
            String l = lines.get(i);
            if (compHeaderIdx < 0 && (l.equalsIgnoreCase("comprehensive questions")
                    || l.equalsIgnoreCase("comprehensive"))) {
                compHeaderIdx = i;
            } else if (ansHeaderIdx < 0 && (l.equalsIgnoreCase("answers")
                    || l.equalsIgnoreCase("answer key") || l.equalsIgnoreCase("answer keys"))) {
                ansHeaderIdx = i;
            } else if (ansHeaderIdx >= 0 && compAnsHeaderIdx < 0
                    && (l.equalsIgnoreCase("comprehensive answers")
                        || l.equalsIgnoreCase("comprehensive answer"))) {
                compAnsHeaderIdx = i;
            }
        }

        // ── Collect question texts ─────────────────────────────────────────────
        // Short-answer: lines[0 .. compHeaderIdx-1]  (or all questions if no comp header)
        int shortEnd = compHeaderIdx >= 0 ? compHeaderIdx
                     : (ansHeaderIdx  >= 0 ? ansHeaderIdx : lines.size());
        List<String> shortQs = new ArrayList<>();
        for (int i = 0; i < shortEnd && shortQs.size() < 50; i++) {
            shortQs.add(lines.get(i));
        }

        // Comprehensive: lines[compHeaderIdx+1 .. ansHeaderIdx-1]  (max 5)
        List<String> compQs = new ArrayList<>();
        if (compHeaderIdx >= 0) {
            int compEnd = ansHeaderIdx >= 0 ? ansHeaderIdx : lines.size();
            for (int i = compHeaderIdx + 1; i < compEnd && compQs.size() < 5; i++) {
                compQs.add(lines.get(i));
            }
        }

        // ── Collect answer texts ───────────────────────────────────────────────
        // Short-answer answers: lines[ansHeaderIdx+1 .. compAnsHeaderIdx-1]
        List<String> shortAnswers = new ArrayList<>();
        if (ansHeaderIdx >= 0) {
            int saEnd = compAnsHeaderIdx >= 0 ? compAnsHeaderIdx : lines.size();
            for (int i = ansHeaderIdx + 1; i < saEnd; i++) {
                String a = lines.get(i).trim().replaceFirst("^\\d+[.)\\s]+", "").trim();
                if (!a.isEmpty()) shortAnswers.add(a);
            }
        }

        // Comprehensive answers: lines[compAnsHeaderIdx+1 .. end]
        List<String> compAnswers = new ArrayList<>();
        if (compAnsHeaderIdx >= 0) {
            for (int i = compAnsHeaderIdx + 1; i < lines.size(); i++) {
                String a = lines.get(i).trim().replaceFirst("^\\d+[.)\\s]+", "").trim();
                if (!a.isEmpty()) compAnswers.add(a);
            }
        }

        List<Map<String,Object>> result = new ArrayList<>();

        // Short-answer questions
        for (int i = 0; i < shortQs.size(); i++) {
            Map<String,Object> q = new LinkedHashMap<>();
            q.put("type", "ShortAnswer");
            q.put("text", shortQs.get(i));
            q.put("options", null);
            q.put("answer", i < shortAnswers.size() ? shortAnswers.get(i) : null);
            result.add(q);
        }

        // Comprehensive questions
        for (int i = 0; i < compQs.size(); i++) {
            Map<String,Object> q = new LinkedHashMap<>();
            q.put("type", "Comprehensive");
            q.put("text", compQs.get(i));
            q.put("options", null);
            q.put("answer", i < compAnswers.size() ? compAnswers.get(i) : null);
            result.add(q);
        }

        return result;
    }

    // =========================================================================
    // TEACHER MEMBER PORTAL ENDPOINTS
    // =========================================================================

    /** Returns the class the logged-in teacher is assigned to, plus their exams. */
    @GetMapping("/api/member/ss/teacher/dashboard")
    public ResponseEntity<?> teacherDashboard(HttpServletRequest req) {
        HttpSession session = req.getSession(false);
        // Return empty map (not 401/403) for non-member sessions so the browser
        // doesn't log a noisy network error when staff visit /memberHome.
        String[] ssId = resolveSsIdentity(session);
        if (ssId == null) return ResponseEntity.ok(Map.of());
        String memberRef = ssId[0];
        String clientId  = ssId[1];

        // Find teacher record for this member — try MBR token first, then integer memberId fallback
        List<SsTeacher> teachers = teacherRepo.findByMemberRefAndClientId(memberRef, clientId);
        if (teachers.isEmpty()) {
            // Fallback: old records may have stored the integer memberId instead of the MBR token
            Object memberIdObj = session.getAttribute("memberId");
            if (memberIdObj != null) {
                String memberIdStr = String.valueOf(memberIdObj);
                teachers = teacherRepo.findByMemberRefAndClientId(memberIdStr, clientId);
                // If found via fallback, self-heal the record to use the correct MBR token
                if (!teachers.isEmpty() && memberRef != null && !memberRef.isBlank()) {
                    for (SsTeacher t : teachers) { t.setMemberRef(memberRef); teacherRepo.save(t); }
                }
            }
        }
        // Fallback: appClientId may not be in session (family lookup failed at login).
        // Try matching by memberRef alone, ignoring clientId scope.
        if (teachers.isEmpty() && memberRef != null && !memberRef.isBlank()) {
            teachers = teacherRepo.findByMemberRefAndDeleteFlagFalse(memberRef);
        }
        // Final fallback: teacher was added by name only (no memberRef) — match by login email/username
        if (teachers.isEmpty()) {
            String username = session != null ? (String) session.getAttribute("username") : null;
            if (username != null && !username.isBlank()) {
                List<SsTeacher> byEmail = teacherRepo.findByEmailAndDeleteFlagFalse(username);
                if (!byEmail.isEmpty()) {
                    // Self-heal: stamp memberRef so future lookups skip the email fallback
                    if (memberRef != null && !memberRef.isBlank()) {
                        for (SsTeacher t : byEmail) {
                            if (t.getMemberRef() == null || t.getMemberRef().isBlank()) {
                                t.setMemberRef(memberRef);
                                teacherRepo.save(t);
                            }
                        }
                    }
                    teachers = byEmail;
                }
            }
        }
        // Not a teacher — return empty list so the browser doesn't log a noisy 403
        if (teachers.isEmpty()) return ResponseEntity.ok(List.of());

        List<Map<String,Object>> classInfoList = new ArrayList<>();
        for (SsTeacher teacher : teachers) {
            SsClass cls = classRepo.findById(teacher.getClassId()).orElse(null);
            if (cls == null) continue;
            Map<String,Object> info = new LinkedHashMap<>();
            info.put("teacherId", teacher.getId());
            info.put("classId", cls.getId());
            info.put("className", cls.getClassName());
            info.put("description", cls.getDescription());
            classInfoList.add(info);
        }
        return ResponseEntity.ok(classInfoList);
    }

    /** Teacher: get exams for their class. */
    @GetMapping("/api/member/ss/teacher/classes/{classId}/exams")
    public ResponseEntity<?> teacherGetExams(@PathVariable Long classId, HttpServletRequest req) {
        if (!isTeacherOfClass(req, classId)) return ResponseEntity.status(403).build();
        List<SsExam> exams = examRepo.findByClassIdAndDeleteFlagFalseOrderByIdDesc(classId);
        List<Map<String,Object>> result = exams.stream().map(e -> {
            Map<String,Object> r = new LinkedHashMap<>();
            r.put("id", e.getId());
            r.put("examTitle", e.getExamTitle());
            r.put("status", e.getStatus());
            r.put("durationMinutes", e.getDurationMinutes());
            r.put("requiredQuestions", e.getRequiredQuestions());
            r.put("totalQuestions", questionRepo.findByExamIdAndDeleteFlagFalseOrderBySortOrderAsc(e.getId()).size());
            r.put("copyToHoh", e.isCopyToHoh());
            return r;
        }).collect(Collectors.toList());
        return ResponseEntity.ok(result);
    }

    /** Teacher: create exam for their class. */
    @PostMapping("/api/member/ss/teacher/classes/{classId}/exams")
    @Transactional
    public ResponseEntity<?> teacherCreateExam(@PathVariable Long classId,
                                                @RequestBody Map<String,Object> body,
                                                HttpServletRequest req) {
        if (!isTeacherOfClass(req, classId)) return ResponseEntity.status(403).build();
        String clientId = teacherClientId(req);
        SsExam e = new SsExam();
        e.setClassId(classId);
        e.setClientId(clientId);
        e.setExamTitle(str(body.get("examTitle")));
        e.setStatus("Draft");
        if (body.get("durationMinutes") != null)
            e.setDurationMinutes(Integer.parseInt(str(body.get("durationMinutes"))));
        if (body.get("requiredQuestions") != null)
            e.setRequiredQuestions(Integer.parseInt(str(body.get("requiredQuestions"))));
        examRepo.save(e);
        return ResponseEntity.ok(Map.of("id", e.getId()));
    }

    /** Teacher: update exam (title, duration, status). */
    @PutMapping("/api/member/ss/teacher/exams/{examId}")
    @Transactional
    public ResponseEntity<?> teacherUpdateExam(@PathVariable Long examId,
                                                @RequestBody Map<String,Object> body,
                                                HttpServletRequest req) {
        SsExam e = examRepo.findById(examId).orElse(null);
        if (e == null) return ResponseEntity.status(404).build();
        if (!isTeacherOfClass(req, e.getClassId())) return ResponseEntity.status(403).build();
        if (body.containsKey("examTitle")) e.setExamTitle(str(body.get("examTitle")));
        if (body.containsKey("status"))    e.setStatus(str(body.get("status")));
        if (body.containsKey("durationMinutes") && body.get("durationMinutes") != null)
            e.setDurationMinutes(Integer.parseInt(str(body.get("durationMinutes"))));
        if (body.containsKey("requiredQuestions") && body.get("requiredQuestions") != null)
            e.setRequiredQuestions(Integer.parseInt(str(body.get("requiredQuestions"))));
        if (body.containsKey("copyToHoh"))
            e.setCopyToHoh(Boolean.TRUE.equals(body.get("copyToHoh")) || "true".equalsIgnoreCase(str(body.get("copyToHoh"))));
        examRepo.save(e);
        return ResponseEntity.ok(Map.of("status", "ok"));
    }

    /**
     * Teacher: republish a closed exam so students can retake it.
     * Soft-deletes all existing submissions + their answers, then sets status → Published.
     */
    @PostMapping("/api/member/ss/teacher/exams/{examId}/republish")
    @Transactional
    public ResponseEntity<?> teacherRepublishExam(@PathVariable Long examId, HttpServletRequest req) {
        SsExam e = examRepo.findById(examId).orElse(null);
        if (e == null) return ResponseEntity.status(404).build();
        if (!isTeacherOfClass(req, e.getClassId())) return ResponseEntity.status(403).build();
        List<SsSubmission> subs = submissionRepo.findByExamIdAndDeleteFlagFalse(examId);
        for (SsSubmission sub : subs) {
            List<SsAnswer> answers = answerRepo.findBySubmissionId(sub.getId());
            answerRepo.deleteAll(answers);
            sub.setDeleteFlag(true);
            submissionRepo.save(sub);
        }
        e.setStatus("Published");
        examRepo.save(e);
        return ResponseEntity.ok(Map.of("status", "republished", "clearedSubmissions", subs.size()));
    }

    /** Teacher: get questions for an exam. */
    @GetMapping("/api/member/ss/teacher/exams/{examId}/questions")
    public ResponseEntity<?> teacherGetQuestions(@PathVariable Long examId, HttpServletRequest req) {
        SsExam e = examRepo.findById(examId).orElse(null);
        if (e == null) return ResponseEntity.status(404).build();
        if (!isTeacherOfClass(req, e.getClassId())) return ResponseEntity.status(403).build();
        return ResponseEntity.ok(questionRepo.findByExamIdAndDeleteFlagFalseOrderBySortOrderAsc(examId));
    }

    /** Teacher: upload question paper for an exam. */
    @PostMapping("/api/member/ss/teacher/exams/{examId}/upload")
    @Transactional
    public ResponseEntity<?> teacherUploadPaper(@PathVariable Long examId,
                                                 @RequestParam("file") MultipartFile file,
                                                 HttpServletRequest req) throws IOException {
        SsExam e = examRepo.findById(examId).orElse(null);
        if (e == null) return ResponseEntity.status(404).build();
        if (!isTeacherOfClass(req, e.getClassId())) return ResponseEntity.status(403).build();
        // Reuse admin upload logic
        return uploadQuestionPaperInternal(examId, file, teacherClientId(req));
    }

    /** Teacher: get submissions for an exam. */
    @GetMapping("/api/member/ss/teacher/exams/{examId}/submissions")
    public ResponseEntity<?> teacherGetSubmissions(@PathVariable Long examId, HttpServletRequest req) {
        SsExam e = examRepo.findById(examId).orElse(null);
        if (e == null) return ResponseEntity.status(404).build();
        if (!isTeacherOfClass(req, e.getClassId())) return ResponseEntity.status(403).build();
        List<SsSubmission> subs = submissionRepo.findByExamIdAndDeleteFlagFalse(examId);
        SsExam exam = examRepo.findById(examId).orElse(null);
        List<Map<String,Object>> result = subs.stream().map(s -> {
            Map<String,Object> r = new LinkedHashMap<>();
            r.put("id", s.getId());
            r.put("studentId", s.getStudentId());
            r.put("status", s.getStatus());
            r.put("startedAt", s.getStartedAt());
            r.put("submittedAt", s.getSubmittedAt());
            r.put("autoMarks", s.getAutoMarks());
            r.put("manualMarks", s.getManualMarks());
            r.put("totalMarks", s.getTotalMarks());
            r.put("durationMinutes", exam != null ? exam.getDurationMinutes() : null);
            r.put("extraTimeMinutes", s.getExtraTimeMinutes());
            r.put("reviewed", s.isReviewed());
            if (s.getStartedAt() != null && s.getSubmittedAt() != null) {
                long mins = java.time.Duration.between(s.getStartedAt(), s.getSubmittedAt()).toMinutes();
                r.put("takenMinutes", mins);
            }
            studentRepo.findById(s.getStudentId()).ifPresent(st -> r.put("studentName", st.getStudentName()));
            return r;
        }).collect(Collectors.toList());
        return ResponseEntity.ok(result);
    }

    /** Teacher: get answers for a submission. */
    @GetMapping("/api/member/ss/teacher/submissions/{submissionId}/answers")
    public ResponseEntity<?> teacherGetAnswers(@PathVariable Long submissionId, HttpServletRequest req) {
        SsSubmission sub = submissionRepo.findById(submissionId).orElse(null);
        if (sub == null) return ResponseEntity.status(404).build();
        SsExam e = examRepo.findById(sub.getExamId()).orElse(null);
        if (e == null || !isTeacherOfClass(req, e.getClassId())) return ResponseEntity.status(403).build();
        List<SsQuestion> questions = questionRepo.findByExamIdOrderBySortOrderAsc(sub.getExamId());
        List<SsAnswer> answers = answerRepo.findBySubmissionId(submissionId);
        // Use merge to handle duplicate questionId keys (keep last answer)
        Map<Long,SsAnswer> ansMap = new java.util.HashMap<>();
        for (SsAnswer a : answers) { ansMap.put(a.getQuestionId(), a); }
        List<Map<String,Object>> items = questions.stream()
                .filter(q -> !q.isDeleteFlag())
                .map(q -> {
                    Map<String,Object> r = new LinkedHashMap<>();
                    r.put("questionId", q.getId());
                    r.put("questionText", q.getQuestionText());
                    r.put("questionType", q.getQuestionType());
                    r.put("optionsJson", q.getOptionsJson());
                    r.put("correctAnswer", q.getCorrectAnswer());
                    r.put("marks", q.getMarks());
                    r.put("sortOrder", q.getSortOrder());
                    SsAnswer a = ansMap.get(q.getId());
                    r.put("answerId",        a != null ? a.getId()             : null);
                    r.put("answerText",      a != null ? a.getAnswerText()      : null);
                    r.put("isCorrect",       a != null ? a.getIsCorrect()       : null);
                    r.put("manualMarks",     a != null ? a.getManualMarks()     : null);
                    r.put("suggestedMarks",  a != null ? a.getSuggestedMarks()  : null);
                    r.put("matchHighlights", a != null ? a.getMatchHighlights() : null);
                    return r;
                }).collect(Collectors.toList());
        String studentName = studentRepo.findById(sub.getStudentId())
                .map(SsStudent::getStudentName).orElse("");
        // Use LinkedHashMap to allow null values (Map.of() rejects nulls)
        Map<String,Object> resp = new LinkedHashMap<>();
        resp.put("submissionId", submissionId);
        resp.put("studentName", studentName);
        resp.put("autoMarks",   sub.getAutoMarks()   != null ? sub.getAutoMarks()   : 0);
        resp.put("manualMarks", sub.getManualMarks() != null ? sub.getManualMarks() : 0);
        resp.put("totalMarks",  sub.getTotalMarks()  != null ? sub.getTotalMarks()  : 0);
        resp.put("questions",   items);
        return ResponseEntity.ok(resp);
    }


    /** Teacher: extend time for an InProgress submission. */
    @PostMapping("/api/member/ss/teacher/submissions/{submissionId}/extend")
    public ResponseEntity<?> teacherExtendTime(@PathVariable Long submissionId,
                                                @RequestBody Map<String,Object> body,
                                                HttpServletRequest req) {
        SsSubmission sub = submissionRepo.findById(submissionId).orElse(null);
        if (sub == null) return ResponseEntity.status(404).build();
        SsExam e = examRepo.findById(sub.getExamId()).orElse(null);
        if (e == null || !isTeacherOfClass(req, e.getClassId())) return ResponseEntity.status(403).build();
        if (!"InProgress".equals(sub.getStatus()))
            return ResponseEntity.badRequest().body(Map.of("error", "Exam is not in progress."));
        int extra = Integer.parseInt(str(body.getOrDefault("extraMinutes", "0")));
        if (extra <= 0) return ResponseEntity.badRequest().body(Map.of("error", "extraMinutes must be > 0"));
        sub.setExtraTimeMinutes(sub.getExtraTimeMinutes() + extra);
        submissionRepo.save(sub);
        return ResponseEntity.ok(Map.of("extraTimeMinutes", sub.getExtraTimeMinutes()));
    }

    /** Teacher: set manual marks on an answer. */
    @PatchMapping("/api/member/ss/teacher/answers/{answerId}/marks")
    public ResponseEntity<?> teacherSetMarks(@PathVariable Long answerId,
                                              @RequestBody Map<String,Object> body,
                                              HttpServletRequest req) {
        SsAnswer a = answerRepo.findById(answerId).orElse(null);
        if (a == null) return ResponseEntity.status(404).build();
        SsSubmission sub = submissionRepo.findById(a.getSubmissionId()).orElse(null);
        if (sub == null) return ResponseEntity.status(404).build();
        SsExam e = examRepo.findById(sub.getExamId()).orElse(null);
        if (e == null || !isTeacherOfClass(req, e.getClassId())) return ResponseEntity.status(403).build();
        int marks = Integer.parseInt(str(body.get("manualMarks")));
        a.setManualMarks(marks);
        answerRepo.save(a);
        recalcTotal(a.getSubmissionId());
        return ResponseEntity.ok(Map.of("status", "ok"));
    }

    /**
     * Teacher: finalize review for a submission.
     * Accepts a list of { answerId, manualMarks } pairs (for manual-graded questions)
     * plus an optional overallAdjustment (added directly to totalMarks after recalc).
     * Sets status → "Graded" and reviewed → true so the child can see the final score.
     */
    @PostMapping("/api/member/ss/teacher/submissions/{submissionId}/finalize")
    @Transactional
    public ResponseEntity<?> teacherFinalizeReview(@PathVariable Long submissionId,
                                                    @RequestBody Map<String,Object> body,
                                                    HttpServletRequest req) {
        SsSubmission sub = submissionRepo.findById(submissionId).orElse(null);
        if (sub == null) return ResponseEntity.status(404).build();
        SsExam e = examRepo.findById(sub.getExamId()).orElse(null);
        if (e == null || !isTeacherOfClass(req, e.getClassId())) return ResponseEntity.status(403).build();

        // 1. Save per-question manual marks
        @SuppressWarnings("unchecked")
        List<Map<String,Object>> answerMarks = (List<Map<String,Object>>) body.getOrDefault("answerMarks", List.of());
        for (Map<String,Object> am : answerMarks) {
            Long answerId = Long.parseLong(str(am.get("answerId")));
            int marks = Integer.parseInt(str(am.getOrDefault("manualMarks", "0")));
            answerRepo.findById(answerId).ifPresent(a -> {
                a.setManualMarks(marks);
                answerRepo.save(a);
            });
        }

        // 2. Recalculate totals (auto + sum of all manual marks)
        List<SsAnswer> answers = answerRepo.findBySubmissionId(submissionId);
        int manualTotal = answers.stream()
                .mapToInt(a -> a.getManualMarks() != null ? a.getManualMarks() : 0).sum();
        sub.setManualMarks(manualTotal);
        int autoM = sub.getAutoMarks() != null ? sub.getAutoMarks() : 0;

        // 3. Optional overall adjustment
        int adjustment = 0;
        if (body.get("overallAdjustment") != null) {
            try { adjustment = Integer.parseInt(str(body.get("overallAdjustment"))); } catch (Exception ex) { /* ignore */ }
        }
        sub.setTotalMarks(autoM + manualTotal + adjustment);

        // 4. Mark as Graded + reviewed
        sub.setStatus("Graded");
        sub.setReviewed(true);
        submissionRepo.save(sub);

        return ResponseEntity.ok(Map.of(
                "status", "ok",
                "totalMarks", sub.getTotalMarks(),
                "autoMarks", autoM,
                "manualMarks", manualTotal
        ));
    }

    /**
     * Teacher: email the finalized graded answer sheet to the student's contact email
     * and the Head-of-Household.  Accessible only by the assigned teacher.
     * Also marks the submission as reviewed so the student portal shows the final result.
     */
    @PostMapping("/api/member/ss/teacher/submissions/{submissionId}/send-result")
    @Transactional
    public ResponseEntity<?> teacherSendResult(@PathVariable Long submissionId,
                                                HttpServletRequest req) {
        SsSubmission sub = submissionRepo.findById(submissionId).orElse(null);
        if (sub == null) return ResponseEntity.status(404).build();
        SsExam exam = examRepo.findById(sub.getExamId()).orElse(null);
        if (exam == null || !isTeacherOfClass(req, exam.getClassId()))
            return ResponseEntity.status(403).build();

        SsStudent student = studentRepo.findById(sub.getStudentId()).orElse(null);
        if (student == null) return ResponseEntity.status(404).build();

        List<SsQuestion> questions = questionRepo.findByExamIdAndDeleteFlagFalseOrderBySortOrderAsc(sub.getExamId());
        List<SsAnswer>   answers   = answerRepo.findBySubmissionId(submissionId);
        Map<Long,SsAnswer> ansMap  = new java.util.HashMap<>();
        for (SsAnswer a : answers) ansMap.put(a.getQuestionId(), a);

        // Ensure submission is marked reviewed
        sub.setReviewed(true);
        if (!"Graded".equals(sub.getStatus())) sub.setStatus("Graded");
        submissionRepo.save(sub);

        String clientId = teacherClientId(req);
        if (clientId == null) clientId = sub.getClientId();

        String subject = exam.getExamTitle() + " — Graded Result for " + student.getStudentName();
        String html    = buildGradedResultEmail(student, exam, sub, questions, ansMap);

        int sent = 0;
        List<String> errors = new ArrayList<>();

        // Send to student contact email
        String studentEmail = student.getContactEmail();
        if (studentEmail != null && !studentEmail.isBlank()) {
            try {
                emailService.sendOrgEmail(studentEmail.trim(), subject, html, clientId);
                sent++;
            } catch (Exception ex) {
                errors.add("Student email failed: " + ex.getMessage());
                log.error("Failed to send graded result to student email", ex);
            }
        }

        // Send to Head-of-Household (if different from student email)
        if (student.getFamilyMemberId() != null) {
            try {
                String hohEmail = familyMemberRepo.findHohEmailByFamilyMemberId(student.getFamilyMemberId());
                if (hohEmail != null && !hohEmail.isBlank() &&
                        !hohEmail.equalsIgnoreCase(studentEmail)) {
                    emailService.sendOrgEmail(hohEmail.trim(), subject, html, clientId);
                    sent++;
                }
            } catch (Exception ex) {
                errors.add("HoH email failed: " + ex.getMessage());
                log.error("Failed to send graded result to HoH", ex);
            }
        }

        Map<String,Object> resp = new LinkedHashMap<>();
        resp.put("status", errors.isEmpty() ? "sent" : "partial");
        resp.put("emailsSent", sent);
        if (!errors.isEmpty()) resp.put("errors", errors);
        return ResponseEntity.ok(resp);
    }

    /** Teacher: add/edit a question directly in their exam. */
    @PostMapping("/api/member/ss/teacher/exams/{examId}/questions/{questionId}")
    @Transactional
    public ResponseEntity<?> teacherUpdateQuestion(@PathVariable Long examId,
                                                    @PathVariable Long questionId,
                                                    @RequestBody Map<String,Object> body,
                                                    HttpServletRequest req) {
        SsExam e = examRepo.findById(examId).orElse(null);
        if (e == null || !isTeacherOfClass(req, e.getClassId())) return ResponseEntity.status(403).build();
        SsQuestion q = questionRepo.findById(questionId).orElse(null);
        if (q == null || !q.getExamId().equals(examId)) return ResponseEntity.status(404).build();
        if (body.containsKey("questionText"))  q.setQuestionText(str(body.get("questionText")));
        if (body.containsKey("questionType"))  q.setQuestionType(str(body.get("questionType")));
        if (body.containsKey("optionsJson"))   q.setOptionsJson(str(body.get("optionsJson")));
        if (body.containsKey("correctAnswer")) q.setCorrectAnswer(str(body.get("correctAnswer")));
        if (body.containsKey("marks")) {
            try { q.setMarks(Integer.parseInt(str(body.get("marks")))); } catch (Exception ex) { /* ignore */ }
        }
        questionRepo.save(q);
        return ResponseEntity.ok(Map.of("status", "ok"));
    }

    // =========================================================================
    // PRIVATE HELPERS
    // =========================================================================

    /**
     * Extracts the org clientId for a logged-in teacher (appClientId from session,
     * falling back to clientId for older sessions).
     */
    private static String teacherClientId(HttpServletRequest req) {
        HttpSession session = req.getSession(false);
        if (session == null) return null;
        String cid = (String) session.getAttribute("appClientId");
        if (cid == null) cid = (String) session.getAttribute("clientId");
        return cid;
    }

    /**
     * Shared question-paper upload logic used by both admin and teacher endpoints.
     * Soft-deletes existing questions, parses the uploaded file, and inserts new ones.
     */
    private ResponseEntity<?> uploadQuestionPaperInternal(Long examId, MultipartFile file, String clientId)
            throws java.io.IOException {
        if (clientId == null) return ResponseEntity.status(401).build();
        SsExam exam = examRepo.findById(examId).orElse(null);
        if (exam == null) return ResponseEntity.status(404).build();

        String text = extractText(file);
        List<Map<String,Object>> parsed = parseQuestions(text);

        // Soft-delete existing questions
        List<SsQuestion> old = questionRepo.findByExamIdAndDeleteFlagFalseOrderBySortOrderAsc(examId);
        old.forEach(q -> { q.setDeleteFlag(true); questionRepo.save(q); });

        int order = 1;
        List<SsQuestion> saved = new ArrayList<>();
        for (Map<String,Object> pq : parsed) {
            SsQuestion q = new SsQuestion();
            q.setClientId(clientId);
            q.setExamId(examId);
            q.setQuestionType(str(pq.get("type")));
            q.setQuestionText(str(pq.get("text")));
            q.setOptionsJson(str(pq.get("options")));
            q.setCorrectAnswer(str(pq.get("answer")));
            q.setMarks("Comprehensive".equals(str(pq.get("type"))) ? 5 : 1);
            q.setSortOrder(order++);
            saved.add(questionRepo.save(q));
        }
        exam.setTotalQuestions(saved.size());
        examRepo.save(exam);
        return ResponseEntity.ok(Map.of("status", "parsed", "count", saved.size(), "questions", saved));
    }

    /** Null-safe Object → String helper. */
    private static String str(Object o) {
        return o == null ? null : o.toString().trim();
    }

    /** Returns true if the logged-in member is a teacher of the given class. */
    private boolean isTeacherOfClass(HttpServletRequest req, Long classId) {
        HttpSession session = req.getSession(false);
        if (session == null) return false;
        String memberRef = (String) session.getAttribute("clientId");
        String clientId  = (String) session.getAttribute("appClientId");
        if (clientId == null) clientId = memberRef;
        if (classId == null) return false;
        List<SsTeacher> teachers = teacherRepo.findByMemberRefAndClientId(memberRef, clientId);
        if (teachers.isEmpty()) {
            Object memberIdObj = session.getAttribute("memberId");
            if (memberIdObj != null) {
                teachers = teacherRepo.findByMemberRefAndClientId(String.valueOf(memberIdObj), clientId);
            }
        }
        if (teachers.isEmpty() && memberRef != null && !memberRef.isBlank()) {
            teachers = teacherRepo.findByMemberRefAndDeleteFlagFalse(memberRef);
        }
        if (teachers.isEmpty()) {
            String username = (String) session.getAttribute("username");
            if (username != null && !username.isBlank()) {
                List<SsTeacher> byEmail = teacherRepo.findByEmailAndDeleteFlagFalse(username);
                if (!byEmail.isEmpty()) {
                    if (memberRef != null && !memberRef.isBlank()) {
                        for (SsTeacher t : byEmail) {
                            if (t.getMemberRef() == null || t.getMemberRef().isBlank()) {
                                t.setMemberRef(memberRef);
                                teacherRepo.save(t);
                            }
                        }
                    }
                    teachers = byEmail;
                }
            }
        }
        final Long cid2 = classId;
        return teachers.stream().anyMatch(t -> cid2.equals(t.getClassId()));
    }

    /**
     * Builds the HTML email sent to a teacher when a student submits an exam.
     */
    private String buildSubmissionEmail(SsStudent student, SsExam exam,
                                        List<SsQuestion> questions,
                                        Map<Long, SsAnswer> ansMap,
                                        int autoMarks) {
        StringBuilder sb = new StringBuilder();
        sb.append("<div style='font-family:sans-serif;max-width:700px;margin:auto;'>")
          .append("<h2 style='color:#673147;'>").append(esc(exam.getExamTitle())).append(" Exam Submission</h2>")
          .append("<p><strong>Student:</strong> ").append(esc(student.getStudentName())).append("</p>")
          .append("<p><strong>Exam:</strong> ").append(esc(exam.getExamTitle())).append("</p>")
          .append("<p><strong>Auto Score:</strong> ").append(autoMarks).append("</p>")
          .append("<hr style='margin:16px 0;'/>");

        int qNum = 0;
        for (SsQuestion q : questions) {
            if (q.isDeleteFlag()) continue;
            qNum++;
            SsAnswer a = ansMap.get(q.getId());
            String answerText = (a != null && a.getAnswerText() != null && !a.getAnswerText().isBlank())
                    ? a.getAnswerText() : null;

            boolean autoGradable = List.of("MCQ", "TrueFalse", "FillBlank").contains(q.getQuestionType());
            Boolean isCorrect = null;
            if (answerText != null && autoGradable
                    && q.getCorrectAnswer() != null && !q.getCorrectAnswer().isBlank()) {
                isCorrect = q.getCorrectAnswer().trim().equalsIgnoreCase(answerText.trim());
            } else if (a != null) {
                isCorrect = a.getIsCorrect();
            }

            String verdictHtml = "";
            if (Boolean.TRUE.equals(isCorrect)) {
                verdictHtml = " <span style='color:#2e7d32;font-weight:700;'>&#10003; Correct</span>";
            } else if (Boolean.FALSE.equals(isCorrect)) {
                verdictHtml = " <span style='color:#c62828;font-weight:700;'>&#10007; Incorrect</span>";
            }

            sb.append("<div style='margin-bottom:14px;padding:10px 14px;border:1px solid #e0d6e8;border-radius:8px;background:#fdfafd;'>")
              .append("<p style='margin:0 0 6px;font-weight:700;color:#333;'>Q").append(qNum)
              .append(". ").append(esc(q.getQuestionText())).append("</p>")
              .append("<p style='margin:0;font-size:13px;'><strong>Answer:</strong> ");

            if (answerText != null) {
                sb.append(esc(answerText)).append(verdictHtml);
            } else {
                sb.append("<em style='color:#aaa;'>No answer provided</em>");
            }
            sb.append("</p>");

            if (Boolean.FALSE.equals(isCorrect) && q.getCorrectAnswer() != null && !q.getCorrectAnswer().isBlank()) {
                sb.append("<p style='margin:4px 0 0;font-size:12px;color:#2e7d32;'><strong>Correct:</strong> ")
                  .append(esc(q.getCorrectAnswer())).append("</p>");
            }
            sb.append("</div>");
        }
        sb.append("</div>");
        return sb.toString();
    }

    /**
     * Builds the HTML email sent to a student / Head-of-Household after a teacher
     * finalises marks and triggers "Send to Parent".
     */
    private String buildGradedResultEmail(SsStudent student, SsExam exam,
                                          SsSubmission sub,
                                          List<SsQuestion> questions,
                                          Map<Long, SsAnswer> ansMap) {
        int totalMax    = questions.stream().filter(q -> !q.isDeleteFlag())
                                   .mapToInt(q -> q.getMarks() != null ? q.getMarks() : 1).sum();
        int autoMarks   = sub.getAutoMarks()   != null ? sub.getAutoMarks()   : 0;
        int manualMarks = sub.getManualMarks() != null ? sub.getManualMarks() : 0;
        int finalScore  = autoMarks + manualMarks;

        StringBuilder sb = new StringBuilder();
        sb.append("<div style='font-family:sans-serif;max-width:700px;margin:auto;'>")
          .append("<h2 style='color:#673147;'>").append(esc(exam.getExamTitle())).append(" — Graded Result</h2>")
          .append("<p><strong>Student:</strong> ").append(esc(student.getStudentName())).append("</p>")
          .append("<p><strong>Exam:</strong> ").append(esc(exam.getExamTitle())).append("</p>")
          .append("<p style='font-size:16px;'><strong>Final Score:</strong> <span style='color:#673147;font-size:20px;font-weight:700;'>")
          .append(finalScore).append(" / ").append(totalMax).append("</span></p>")
          .append("<hr style='margin:16px 0;'/>");

        int qNum = 0;
        for (SsQuestion q : questions) {
            if (q.isDeleteFlag()) continue;
            qNum++;
            SsAnswer a = ansMap.get(q.getId());
            String answerText = (a != null && a.getAnswerText() != null && !a.getAnswerText().isBlank())
                    ? a.getAnswerText() : null;
            int    qMax = q.getMarks() != null ? q.getMarks() : 1;
            String qt   = q.getQuestionType() != null ? q.getQuestionType() : "";

            boolean isComprehensive = "Comprehensive".equals(qt) || "LongAnswer".equals(qt);
            boolean autoGradable    = List.of("MCQ", "TrueFalse", "FillBlank", "ShortAnswer").contains(qt);

            Boolean isCorrect     = null;
            int     qMarksAwarded = 0;
            String  verdictHtml   = "";

            if (isComprehensive) {
                qMarksAwarded = (a != null && a.getManualMarks() != null) ? a.getManualMarks() : 0;
                verdictHtml = " <span style='color:#1565c0;font-weight:700;'>Marks: " + qMarksAwarded + " / " + qMax + "</span>";
            } else if (autoGradable && answerText != null
                       && q.getCorrectAnswer() != null && !q.getCorrectAnswer().isBlank()) {
                if ("ShortAnswer".equals(qt) || "FillBlank".equals(qt)) {
                    com.churchgeniuspro.util.AnswerGrader.GradeResult gr =
                            com.churchgeniuspro.util.AnswerGrader.grade(answerText, q.getCorrectAnswer(), qMax);
                    isCorrect     = gr.isCorrect;
                    qMarksAwarded = gr.suggestedMarks;
                } else {
                    isCorrect     = q.getCorrectAnswer().trim().equalsIgnoreCase(answerText.trim());
                    qMarksAwarded = Boolean.TRUE.equals(isCorrect) ? qMax : 0;
                }
                if (Boolean.TRUE.equals(isCorrect)) {
                    verdictHtml = " <span style='color:#2e7d32;font-weight:700;'>&#10003; Correct ("
                                + qMarksAwarded + "/" + qMax + ")</span>";
                } else if (Boolean.FALSE.equals(isCorrect)) {
                    verdictHtml = " <span style='color:#c62828;font-weight:700;'>&#10007; Incorrect (0/"
                                + qMax + ")</span>";
                } else {
                    verdictHtml = " <span style='color:#e65100;font-weight:700;'>Partial: "
                                + qMarksAwarded + "/" + qMax + "</span>";
                }
            } else if (a != null && a.getIsCorrect() != null) {
                isCorrect     = a.getIsCorrect();
                qMarksAwarded = Boolean.TRUE.equals(isCorrect) ? qMax : 0;
                verdictHtml   = Boolean.TRUE.equals(isCorrect)
                    ? " <span style='color:#2e7d32;font-weight:700;'>&#10003; Correct (" + qMax + "/" + qMax + ")</span>"
                    : " <span style='color:#c62828;font-weight:700;'>&#10007; Incorrect (0/" + qMax + ")</span>";
            }

            sb.append("<div style='margin-bottom:14px;padding:10px 14px;border:1px solid #e0d6e8;border-radius:8px;background:#fdfafd;'>")
              .append("<p style='margin:0 0 4px;font-weight:700;color:#333;'>Q").append(qNum)
              .append(". ").append(esc(q.getQuestionText()))
              .append(" <span style='font-size:11px;color:#888;font-weight:400;'>[").append(esc(qt)).append("]</span></p>")
              .append("<p style='margin:0;font-size:13px;'><strong>Answer:</strong> ");

            if (answerText != null) {
                sb.append(esc(answerText)).append(verdictHtml);
            } else {
                sb.append("<em style='color:#aaa;'>No answer provided</em>").append(verdictHtml);
            }
            sb.append("</p>");

            if (Boolean.FALSE.equals(isCorrect) && q.getCorrectAnswer() != null && !q.getCorrectAnswer().isBlank()) {
                sb.append("<p style='margin:4px 0 0;font-size:12px;color:#2e7d32;'><strong>Correct Answer:</strong> ")
                  .append(esc(q.getCorrectAnswer())).append("</p>");
            }
            sb.append("</div>");
        }

        sb.append("<p style='margin-top:20px;font-size:12px;color:#888;'>This is an automated message from ChurchGenius Pro.</p>")
          .append("</div>");
        return sb.toString();
    }

    /** HTML-escapes a string for safe insertion into email body. */
    private static String esc(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    /**
     * Resolves the SS identity (memberRef, clientId) for any authenticated session.
     */
    private String[] resolveSsIdentity(HttpSession session) {
        if (session == null) return null;
        Object role = session.getAttribute("role");
        boolean isMember = "Member".equals(role) || "Child".equals(role);
        if (isMember) {
            String memberRef = (String) session.getAttribute("clientId");
            String clientId  = (String) session.getAttribute("appClientId");
            if (clientId == null) clientId = memberRef;
            return new String[]{ memberRef, clientId };
        }
        if (isStaffSession(session)) {
            String memberRef = (String) session.getAttribute("memberRef");
            String clientId  = (String) session.getAttribute("appClientId");
            if (memberRef != null && clientId != null) return new String[]{ memberRef, clientId };
            return null;
        }
        return null;
    }

    /**
     * Resolves the SsStudent record for the logged-in Member/Child session.
     * Tries five strategies: memberRef+clientId, familyMemberId, memberRef alone,
     * DB lookup by memberRef, and contactEmail match.
     */
    private SsStudent resolveStudent(HttpSession session) {
        if (session == null) return null;
        String[] ssId = resolveSsIdentity(session);
        if (ssId == null) return null;
        String memberRef = ssId[0];
        String clientId  = ssId[1];

        SsStudent s = studentRepo.findByMemberRefAndClientIdAndDeleteFlagFalse(memberRef, clientId).orElse(null);
        if (s != null) return s;

        Object midObj = session.getAttribute("memberId");
        if (midObj instanceof Number) {
            Integer familyMemberId = ((Number) midObj).intValue();
            s = studentRepo.findByFamilyMemberIdAndClientIdAndDeleteFlagFalse(familyMemberId, clientId).orElse(null);
            if (s != null) {
                if (s.getMemberRef() == null || s.getMemberRef().isBlank()) {
                    s.setMemberRef(memberRef); studentRepo.save(s);
                }
                return s;
            }
            boolean clientIdIsMbrToken = clientId != null && clientId.toUpperCase().startsWith("MBR");
            if (clientIdIsMbrToken) {
                List<SsStudent> byFamId = studentRepo.findByFamilyMemberIdAndDeleteFlagFalse(familyMemberId);
                if (!byFamId.isEmpty()) {
                    s = byFamId.get(0);
                    if (s.getMemberRef() == null || s.getMemberRef().isBlank()) {
                        s.setMemberRef(memberRef); studentRepo.save(s);
                    }
                    return s;
                }
            }
        }

        List<SsStudent> byRef = studentRepo.findByMemberRefAndDeleteFlagFalse(memberRef);
        if (!byRef.isEmpty()) return byRef.get(0);

        FamilyMember fm4 = familyMemberRepo.findByMemberRef(memberRef).orElse(null);
        if (fm4 != null) {
            List<SsStudent> byFamId = studentRepo.findByFamilyMemberIdAndDeleteFlagFalse(fm4.getId());
            if (!byFamId.isEmpty()) {
                s = byFamId.get(0);
                if (s.getMemberRef() == null || s.getMemberRef().isBlank()) {
                    s.setMemberRef(memberRef); studentRepo.save(s);
                }
                return s;
            }
        }

        FamilyMember fm5 = (fm4 != null) ? fm4 : familyMemberRepo.findByMemberRef(memberRef).orElse(null);
        if (fm5 == null && midObj instanceof Number) {
            fm5 = familyMemberRepo.findById(((Number) midObj).intValue()).orElse(null);
        }
        if (fm5 != null) {
            String email = fm5.getEmail();
            if (email != null && !email.isBlank()) {
                final Integer selfMemberId = fm5.getId();
                boolean clientIdIsOrg = clientId != null && !clientId.toUpperCase().startsWith("MBR");
                List<SsStudent> byEmail = clientIdIsOrg
                        ? studentRepo.findByContactEmailAndClientIdAndDeleteFlagFalse(email.trim(), clientId)
                        : studentRepo.findByContactEmailAndDeleteFlagFalse(email.trim());
                if (!byEmail.isEmpty()) {
                    s = byEmail.stream()
                            .filter(e -> selfMemberId.equals(e.getFamilyMemberId()) || e.getFamilyMemberId() == null)
                            .min(java.util.Comparator.comparingInt(
                                    e -> selfMemberId.equals(e.getFamilyMemberId()) ? 0 : 1))
                            .orElse(null);
                    if (s != null) {
                        boolean changed = false;
                        if (s.getFamilyMemberId() == null) { s.setFamilyMemberId(selfMemberId); changed = true; }
                        if (s.getMemberRef() == null || s.getMemberRef().isBlank()) { s.setMemberRef(memberRef); changed = true; }
                        if (changed) studentRepo.save(s);
                        return s;
                    }
                }
            }
        }
        return null;
    }

    /** Returns true when the session belongs to a Staff Portal user (not a Member/Child). */
    private boolean isStaffSession(HttpSession session) {
        if (session == null) return false;
        Object username = session.getAttribute("username");
        if (username == null) return false;
        Object church = session.getAttribute("church");
        return !Boolean.TRUE.equals(church) && !"true".equalsIgnoreCase(String.valueOf(church));
    }
}
