package com.gaurav.lld.amazonlocker.domain;

import java.time.Instant;

public record AccessCredential(String lockerId, String value, Instant expiresAt) {
}
