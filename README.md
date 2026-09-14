# Amazon Locker Service — Low-Level Design

This is an independent Java design for an Amazon Locker-style service. It is deliberately a small, executable in-memory model: the aim is to make domain boundaries, lifecycle rules, and concurrency decisions easy to inspect in an SDE-3 interview.

## Architecture and reasoning

```text
API/controller
    |
LockerService (use-case orchestration)
  /        |             \
repository  notification    credential lifecycle
    |
LockerLocation -> Locker -> Parcel
```

## Source layout

```text
src/com/gaurav/lld/amazonlocker/
  app/            executable demo
  domain/         entities, value objects, enums, credentials
  service/        workflow orchestration (`LockerService`)
  port/           persistence and notification interfaces
  infrastructure/ in-memory repository and console notification adapter
```

`Locker` owns physical state (`AVAILABLE`, `RESERVED`, `OCCUPIED`). This prevents a controller or notification sender from changing occupancy arbitrarily. `LockerService` coordinates cross-entity workflows, so entities do not depend on persistence or messaging. `LockerRepository` and `NotificationPort` are ports: Spring/JPA, SMS, email, or event-bus adapters can replace the in-memory implementations without changing domain logic.

## Important design choices

| Choice | Reason |
|---|---|
| Exact `Dimensions`, not only size labels | A parcel must fully fit; enums are merely capacity buckets. |
| Smallest-fitting locker | Preserves large lockers for parcels that cannot fit elsewhere. |
| `RESERVED` distinct from `OCCUPIED` | A driver may reserve but fail before physical placement. |
| Customer code issued after delivery | The parcel must really be in the locker before pickup access is sent. |
| Separate return and courier credentials | A pickup token cannot be reused for another actor or workflow. |
| SHA-256 token hashes stored | A database should not retain raw access secrets. |
| Per-location lock in the in-memory adapter | Makes find-and-reserve atomic and prevents double booking. |

For a multi-instance Spring Boot deployment, replace the in-memory lock with a database transaction: a conditional update or optimistic `@Version` check that changes `AVAILABLE` to `RESERVED`. JVM locking protects only one process.

## Workflows

### Outbound delivery

1. `reserveForDelivery` chooses the smallest available fitting locker and atomically marks it `RESERVED`.
2. `completeDelivery` records physical placement, moving it to `OCCUPIED` and issuing the three-day customer credential.
3. `pickupByCustomer` validates purpose, one-time use, expiry, and opening hours; it then removes the parcel and releases the locker.

### Return

1. `requestReturn` reserves a fitting locker and issues a customer drop-off credential.
2. `dropOffReturn` consumes that credential, stores the return, and creates a separate courier collection credential.
3. `collectReturn` removes the parcel and releases the locker.

### Expiry

`removeExpiredPackages` is intended for a scheduled job. It invalidates expired customer credentials and releases a locker only after package removal is represented by the workflow.

## Run

```bash
javac -d out $(find src -name '*.java')
java -cp out com.gaurav.lld.amazonlocker.app.Main
```

## Production extensions

- Add a `LockerHardwarePort` and require a confirmed door-close event before releasing a locker.
- Add an `OrderRepository` and return-eligibility policy.
- Publish `PackageDelivered`, `ReturnDroppedOff`, and `LockerExpired` events; deliver notifications asynchronously with retry/outbox support.
- Apply rate limits, audit logs, and stronger credential/token policies.
- Use the location's time zone, not UTC, for operating-hours evaluation.
