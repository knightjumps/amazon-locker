package com.gaurav.lld.amazonlocker.domain;

public record Parcel(String id, String orderId, Dimensions dimensions, boolean isReturn) {
}
