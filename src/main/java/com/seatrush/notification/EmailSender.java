package com.seatrush.notification;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;

/**
 * Sends through SMTP when spring.mail.host is configured (MAIL_HOST env var); otherwise logs the
 * email, which is what you want in local development.
 */
@Slf4j
@Component
public class EmailSender {
    private final JavaMailSender mailSender;   // null when no SMTP server is configured
    private final String from;

    public EmailSender(ObjectProvider<JavaMailSender> mailSender,
                       @Value("${app.mail.from:no-reply@seatrush.local}") String from) {
        this.mailSender = mailSender.getIfAvailable();
        this.from = from;
    }

    public void send(String to, String subject, String body) {
        if (mailSender == null) {
            log.info("[email not sent: no SMTP configured] to={} subject=\"{}\"\n{}", to, subject, body);
            return;
        }
        SimpleMailMessage msg = new SimpleMailMessage();
        msg.setFrom(from);
        msg.setTo(to);
        msg.setSubject(subject);
        msg.setText(body);
        mailSender.send(msg);
    }
}
