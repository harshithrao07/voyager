package com.job.scheduler.config;

import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.Test;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MailConfigTest {
    private final JavaMailSender mailSender = new MailConfig().loggingMailSender();

    @Test
    void providesLoggingMailSender() {
        assertThat(mailSender).isNotNull();
    }

    @Test
    void createMimeMessageIsUnsupported() {
        assertThatThrownBy(mailSender::createMimeMessage)
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void createMimeMessageFromStreamIsUnsupported() {
        assertThatThrownBy(() -> mailSender.createMimeMessage(null))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void sendSimpleMessageIsSwallowed() {
        SimpleMailMessage message = new SimpleMailMessage();
        message.setTo("ops@example.com");
        message.setSubject("Alert");

        assertThatCode(() -> mailSender.send(message)).doesNotThrowAnyException();
    }

    @Test
    void sendSimpleMessagesVarargsIsSwallowed() {
        assertThatCode(() -> mailSender.send(new SimpleMailMessage(), new SimpleMailMessage()))
                .doesNotThrowAnyException();
    }

    @Test
    void sendMimeMessageIsSwallowed() {
        assertThatCode(() -> mailSender.send((MimeMessage) null)).doesNotThrowAnyException();
    }

    @Test
    void sendMimeMessagesVarargsIsSwallowed() {
        assertThatCode(() -> mailSender.send(new MimeMessage[0])).doesNotThrowAnyException();
    }
}
