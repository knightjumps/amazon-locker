package com.gaurav.lld.amazonlocker.domain;

import java.time.*;
import java.util.*;

public final class LockerLocation {
    private final String id;
    private final LocalTime open, close;
    private final List<Locker> lockers;

    public LockerLocation(String id, LocalTime open, LocalTime close, List<Locker> lockers) {
        this.id = id;
        this.open = open;
        this.close = close;
        this.lockers = List.copyOf(lockers);
    }

    public String id() {
        return id;
    }

    public boolean openAt(Instant now) {
        LocalTime t = now.atZone(ZoneOffset.UTC).toLocalTime();
        return !t.isBefore(open) && t.isBefore(close);
    }

    public List<Locker> lockers() {
        return lockers;
    }

    public Locker locker(String id) {
        return lockers.stream().filter(l -> l.id().equals(id)).findFirst().orElseThrow();
    }
}
