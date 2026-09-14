package com.gaurav.lld.amazonlocker.domain;

public record Dimensions(int length, int width, int height) {
    public Dimensions {
        if (length <= 0 || width <= 0 || height <= 0)
            throw new IllegalArgumentException("positive dimensions required");
    }

    public boolean fitsIn(Dimensions other) {
        return length <= other.length && width <= other.width && height <= other.height;
    }
}
