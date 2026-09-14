package com.gaurav.lld.amazonlocker.domain;

public final class Locker {
    private final String id;
    private final LockerSize size;
    private LockerStatus status = LockerStatus.AVAILABLE;
    private Parcel parcel;

    public Locker(String id, LockerSize size) {
        this.id = id;
        this.size = size;
    }

    public String id() {
        return id;
    }

    public LockerSize size() {
        return size;
    }

    public boolean available() {
        return status == LockerStatus.AVAILABLE;
    }

    public boolean fits(Parcel p) {
        return p.dimensions().fitsIn(size.dimensions());
    }

    public void reserve() {
        assertState(status == LockerStatus.AVAILABLE);
        status = LockerStatus.RESERVED;
    }

    public void store(Parcel p) {
        assertState(status == LockerStatus.RESERVED);
        parcel = p;
        status = LockerStatus.OCCUPIED;
    }

    public Parcel release() {
        assertState(status == LockerStatus.OCCUPIED);
        Parcel p = parcel;
        parcel = null;
        status = LockerStatus.AVAILABLE;
        return p;
    }

    private void assertState(boolean valid) {
        if (!valid) throw new IllegalStateException("invalid locker state: " + status);
    }
}
