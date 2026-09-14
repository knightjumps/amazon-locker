package com.gaurav.lld.amazonlocker.infrastructure;

import com.gaurav.lld.amazonlocker.port.NotificationPort;

public final class ConsoleNotificationAdapter implements NotificationPort {
    public void notify(String recipient, String message) {
        System.out.println(recipient + ": " + message);
    }
}
