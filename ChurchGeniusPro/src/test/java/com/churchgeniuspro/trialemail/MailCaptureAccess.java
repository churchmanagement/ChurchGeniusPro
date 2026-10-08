package com.churchgeniuspro.trialemail;

import jakarta.mail.Part;

/** Public bridge so integration tests in another package can read a MimeMessage's text. */
public final class MailCaptureAccess {
    private MailCaptureAccess() {}
    public static String text(Part p) throws Exception { return MailCapture.text(p); }
}
