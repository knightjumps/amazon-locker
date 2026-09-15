package com.gaurav.lld.amazonlocker.app;

import com.gaurav.lld.amazonlocker.domain.*;
import com.gaurav.lld.amazonlocker.infrastructure.*;
import com.gaurav.lld.amazonlocker.service.*;

import java.time.*;
import java.util.*;

public final class Main {
    public static void main(String[] args) {
        LockerService service = new LockerService(
                new InMemoryLockerLocationRepository(),
                new ConsoleNotificationAdapter(),
                Clock.fixed(Instant.parse("2026-09-14T12:00:00Z"), ZoneOffset.UTC));

        service.addLocation(new LockerLocation(
                "blr", LocalTime.of(7, 0), LocalTime.of(22, 0),
                List.of(new Locker("S1", LockerSize.SMALL), new Locker("M1", LockerSize.MEDIUM))));

        traceOutboundDeliveryAndPickup(service);
        traceReturnLifecycle(service);
    }

    private static void traceOutboundDeliveryAndPickup(LockerService service) {
        System.out.println("\n=== 1. Outbound delivery and customer pickup ===");

        Parcel outboundParcel = new Parcel("package-101", "order-101", new Dimensions(20, 20, 10), false);
        AccessCredential pickupCredential = service.deliver("blr", outboundParcel);
        System.out.printf("Customer receives pickup code for locker %s: %s%n",
                pickupCredential.lockerId(), pickupCredential.value());

        Parcel collectedParcel = service.pickup(pickupCredential.value());
        System.out.printf("Customer collected %s; locker %s is available again.%n",
                collectedParcel.id(), pickupCredential.lockerId());
    }

    private static void traceReturnLifecycle(LockerService service) {
        System.out.println("\n=== 2. Return request, drop-off, and courier collection ===");

        Parcel returnParcel = new Parcel("return-package-101", "order-101", new Dimensions(20, 20, 10), true);
        AccessCredential dropOffCredential = service.requestReturn("blr", returnParcel);
        System.out.printf("Customer receives return drop-off code for locker %s: %s%n",
                dropOffCredential.lockerId(), dropOffCredential.value());

        AccessCredential courierCredential = service.dropOffReturn(dropOffCredential.value());
        System.out.printf("Customer dropped off %s. Courier receives collection code for locker %s: %s%n",
                returnParcel.id(), courierCredential.lockerId(), courierCredential.value());

        Parcel collectedReturn = service.collectReturn(courierCredential.value());
        System.out.printf("Courier collected %s; locker %s is available again.%n",
                collectedReturn.id(), courierCredential.lockerId());
    }
}
