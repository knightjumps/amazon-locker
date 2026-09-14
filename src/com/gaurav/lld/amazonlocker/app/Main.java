package com.gaurav.lld.amazonlocker.app;

import com.gaurav.lld.amazonlocker.domain.*;
import com.gaurav.lld.amazonlocker.infrastructure.*;
import com.gaurav.lld.amazonlocker.service.*;

import java.time.*;
import java.util.*;

public final class Main {
    public static void main(String[] args) {
        LockerService s = new LockerService(new InMemoryLockerLocationRepository(), new ConsoleNotificationAdapter(), Clock.fixed(Instant.parse("2026-09-14T12:00:00Z"), ZoneOffset.UTC));
        s.addLocation(new LockerLocation("blr", LocalTime.of(7, 0), LocalTime.of(22, 0), List.of(new Locker("S1", LockerSize.SMALL), new Locker("M1", LockerSize.MEDIUM))));
        AccessCredential c = s.deliver("blr", new Parcel("p1", "o1", new Dimensions(20, 20, 10), false));
        s.pickup(c.value());
    }
}
