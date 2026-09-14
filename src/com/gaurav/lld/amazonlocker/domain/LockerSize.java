package com.gaurav.lld.amazonlocker.domain;

public enum LockerSize {
    SMALL(new Dimensions(30, 30, 20)), MEDIUM(new Dimensions(50, 40, 35)), LARGE(new Dimensions(70, 55, 50));
    private final Dimensions dimensions;

    LockerSize(Dimensions d) {
        dimensions = d;
    }

    public Dimensions dimensions() {
        return dimensions;
    }

    public int volume() {
        return dimensions.length() * dimensions.width() * dimensions.height();
    }
}
