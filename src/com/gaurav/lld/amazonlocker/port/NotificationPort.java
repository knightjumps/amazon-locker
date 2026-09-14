package com.gaurav.lld.amazonlocker.port;

public interface NotificationPort {
    void notify(String recipient, String message);
}
