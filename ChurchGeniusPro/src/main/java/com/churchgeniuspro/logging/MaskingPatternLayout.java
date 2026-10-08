package com.churchgeniuspro.logging;

import ch.qos.logback.classic.PatternLayout;
import ch.qos.logback.classic.spi.ILoggingEvent;

/**
 * A {@link PatternLayout} that runs every rendered line through
 * {@link SensitiveDataMasker} before it is handed to an appender.
 *
 * <p>Masking at the <em>layout</em> level rather than the message level is deliberate: it
 * catches the whole rendered line, including the stack trace. Exception messages are a
 * common accidental leak — a failed HTTP call to Plaid or Twilio happily puts the request
 * body, headers and all, into {@code getMessage()}, and that text only appears after the
 * throwable is formatted.
 *
 * <p>Wired up in {@code logback-spring.xml}; every file appender uses it, so there is no
 * path to disk that bypasses it.
 */
public class MaskingPatternLayout extends PatternLayout {

    @Override
    public String doLayout(ILoggingEvent event) {
        return SensitiveDataMasker.mask(super.doLayout(event));
    }
}
