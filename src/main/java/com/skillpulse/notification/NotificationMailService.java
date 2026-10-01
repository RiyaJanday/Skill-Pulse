package com.skillpulse.notification;

import com.skillpulse.auth.AppUser;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;

@Service
public class NotificationMailService {
    private final JavaMailSender mailSender;
    private final String fromAddress;
    public NotificationMailService(JavaMailSender mailSender, @Value("${skillpulse.mail.from}") String fromAddress) {
        this.mailSender = mailSender; this.fromAddress = fromAddress;
    }
    public void send(AppUser user, String subject, String body) {
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(fromAddress);
        message.setTo(user.getEmail());
        message.setSubject(subject);
        message.setText(body);
        mailSender.send(message);
    }
}
