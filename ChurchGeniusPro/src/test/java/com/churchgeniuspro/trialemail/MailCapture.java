package com.churchgeniuspro.trialemail;

import jakarta.mail.Message;
import jakarta.mail.Multipart;
import jakarta.mail.Part;
import jakarta.mail.internet.MimeMessage;
import org.springframework.mail.javamail.JavaMailSender;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** A JavaMailSender that keeps every message handed to it, with real MimeMessages. */
final class MailCapture {
    final JavaMailSender sender = mock(JavaMailSender.class);
    final List<MimeMessage> sent = new ArrayList<>();

    MailCapture() {
        when(sender.createMimeMessage()).thenAnswer(i -> new MimeMessage((jakarta.mail.Session) null));
        doAnswer(i -> { sent.add(i.getArgument(0)); return null; }).when(sender).send(any(MimeMessage.class));
    }

    static List<String> recipients(MimeMessage m) throws Exception {
        List<String> out = new ArrayList<>();
        for (Message.RecipientType t : List.of(Message.RecipientType.TO, Message.RecipientType.CC, Message.RecipientType.BCC)) {
            jakarta.mail.Address[] a = m.getRecipients(t);
            if (a != null) out.addAll(Arrays.stream(a).map(Object::toString).collect(Collectors.toList()));
        }
        return out;
    }

    static String text(Part p) throws Exception {
        Object c = p.getContent();
        if (c instanceof String s) return s;
        if (c instanceof Multipart mp) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < mp.getCount(); i++) sb.append(text(mp.getBodyPart(i)));
            return sb.toString();
        }
        return "";
    }

    List<String> allRecipients() throws Exception {
        List<String> out = new ArrayList<>();
        for (MimeMessage m : sent) out.addAll(recipients(m));
        return out;
    }
}
