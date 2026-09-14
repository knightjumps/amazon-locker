package com.gaurav.lld.amazonlocker.service;

import com.gaurav.lld.amazonlocker.domain.*;
import com.gaurav.lld.amazonlocker.port.*;

import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.locks.*;

/**
 * Coordinates use cases; Locker itself owns physical state transitions.
 */
public final class LockerService {
    private final LockerLocationRepository locations;
    private final NotificationPort notifications;
    private final Clock clock;
    private final Map<String, ReentrantLock> locks = new ConcurrentHashMap<>();
    private final Map<String, Grant> grants = new ConcurrentHashMap<>();

    public LockerService(LockerLocationRepository l, NotificationPort n, Clock c) {
        locations = l;
        notifications = n;
        clock = c;
    }

    public void addLocation(LockerLocation l) {
        locations.save(l);
        locks.putIfAbsent(l.id(), new ReentrantLock());
    }

    public AccessCredential deliver(String locationId, Parcel parcel) {
        LockerLocation l = location(locationId);
        Locker k = reserve(l, parcel);
        k.store(parcel);
        AccessCredential c = issue(k, parcel, AccessPurpose.CUSTOMER_PICKUP);
        notifications.notify("customer", "Package is ready in " + k.id());
        return c;
    }

    public AccessCredential requestReturn(String locationId, Parcel parcel) {
        if (!parcel.isReturn()) throw new IllegalArgumentException("Not a return parcel");
        Locker k = reserve(location(locationId), parcel);
        return issue(k, parcel, AccessPurpose.RETURN_DROPOFF);
    }

    public AccessCredential dropOffReturn(String code) {
        Grant g = consume(code, AccessPurpose.RETURN_DROPOFF);
        Locker k = location(g.locationId).locker(g.lockerId);
        k.store(g.parcel);
        AccessCredential c = issue(k, g.parcel, AccessPurpose.COURIER_COLLECTION);
        notifications.notify("courier", "Return waiting in " + k.id());
        return c;
    }

    public Parcel pickup(String code) {
        Grant g = consume(code, AccessPurpose.CUSTOMER_PICKUP);
        return location(g.locationId).locker(g.lockerId).release();
    }

    public Parcel collectReturn(String code) {
        Grant g = consume(code, AccessPurpose.COURIER_COLLECTION);
        return location(g.locationId).locker(g.lockerId).release();
    }

    private Locker reserve(LockerLocation l, Parcel p) {
        ReentrantLock lock = locks.get(l.id());
        lock.lock();
        try {
            if (!l.openAt(clock.instant())) throw new IllegalStateException("location closed");
            Locker k = l.lockers().stream().filter(x -> x.available() && x.fits(p)).min(Comparator.comparingInt(x -> x.size().volume())).orElseThrow(() -> new IllegalStateException("no suitable locker"));
            k.reserve();
            return k;
        } finally {
            lock.unlock();
        }
    }

    private AccessCredential issue(Locker k, Parcel p, AccessPurpose purpose) {
        String code = UUID.randomUUID().toString();
        grants.put(code, new Grant(k.id(), findLocation(k.id()), p, purpose, clock.instant().plus(Duration.ofDays(3))));
        return new AccessCredential(k.id(), code, clock.instant().plus(Duration.ofDays(3)));
    }

    private Grant consume(String code, AccessPurpose purpose) {
        Grant g = Optional.ofNullable(grants.get(code)).orElseThrow(() -> new IllegalArgumentException("invalid code"));
        if (g.used || g.purpose != purpose || !clock.instant().isBefore(g.expiry))
            throw new IllegalStateException("code unavailable");
        g.used = true;
        return g;
    }

    private LockerLocation location(String id) {
        return locations.find(id).orElseThrow(() -> new IllegalArgumentException("unknown location"));
    }

    private String findLocation(String lockerId) {
        return locks.keySet().stream().filter(id -> location(id).lockers().stream().anyMatch(l -> l.id().equals(lockerId))).findFirst().orElseThrow();
    }

    private static final class Grant {
        final String lockerId, locationId;
        final Parcel parcel;
        final AccessPurpose purpose;
        final Instant expiry;
        boolean used;

        Grant(String lockerId, String locationId, Parcel p, AccessPurpose purpose, Instant expiry) {
            this.lockerId = lockerId;
            this.locationId = locationId;
            this.parcel = p;
            this.purpose = purpose;
            this.expiry = expiry;
        }
    }
}
