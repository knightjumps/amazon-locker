# Amazon Locker Service — Database Design

## Design objective

The most important database invariant is:

> A physical locker can have at most one active assignment at any moment.

A relational database such as PostgreSQL is a strong fit because allocation, credential consumption, package pickup, and locker release require transactions and constraints. Application-level JVM locks alone are insufficient when several service instances are running.

The schema separates relatively static resources—locations and lockers—from changing workflows—packages, assignments, credentials, events, and notifications.

## Access patterns

The schema must efficiently support these operations:

1. Find available lockers at a selected location.
2. Select the smallest locker that fully contains a package.
3. Atomically reserve the selected locker.
4. Record physical package placement.
5. Validate and consume a one-time access code.
6. Record customer pickup and release the locker.
7. Find expired packages and create removal work.
8. Support customer return drop-off and courier collection.
9. Preserve a history of physical locker operations.

## Entity relationships

```text
LOCKER_LOCATION
       │ 1
       │
       │ N
     LOCKER
       │ 1
       │
       │ N
LOCKER_ASSIGNMENT ───── N:1 ───── PACKAGE
       │
       ├──── 1:N ───── ACCESS_CREDENTIAL
       ├──── 1:N ───── LOCKER_EVENT
       └──── 1:N ───── NOTIFICATION_OUTBOX
```

`locker_assignment` is the central workflow entity. It records which package was assigned to which locker, for what purpose, and during which period.

## `locker_location`

Represents one physical Amazon Locker installation.

| Column | Type | Purpose |
|---|---|---|
| `id` | UUID, primary key | Globally unique location ID |
| `name` | VARCHAR | Human-readable location name |
| `address_line` | VARCHAR | Physical address |
| `latitude` | DECIMAL | Nearby-location search |
| `longitude` | DECIMAL | Nearby-location search |
| `timezone` | VARCHAR | Correct interpretation of local operating hours |
| `status` | ENUM/VARCHAR | `ACTIVE`, `INACTIVE`, or `MAINTENANCE` |
| `created_at` | TIMESTAMP | Audit timestamp |
| `updated_at` | TIMESTAMP | Audit timestamp |
| `version` | BIGINT | Optional optimistic-lock version |

Every location needs a time zone. Operating hours should not be interpreted using the server's time zone or UTC.

## `location_operating_hours`

Stores different opening hours for each day of the week.

| Column | Type | Purpose |
|---|---|---|
| `location_id` | UUID, foreign key | Owning locker location |
| `day_of_week` | SMALLINT/ENUM | Monday through Sunday |
| `open_time` | TIME | Opening time in the location's time zone |
| `close_time` | TIME | Closing time in the location's time zone |
| `is_closed` | BOOLEAN | Marks the location closed for that weekday |

Suggested primary key:

```sql
PRIMARY KEY (location_id, day_of_week)
```

## `location_schedule_override`

Supports holidays, temporary closures, and exceptional operating hours.

| Column | Type | Purpose |
|---|---|---|
| `id` | UUID, primary key | Override ID |
| `location_id` | UUID, foreign key | Affected location |
| `calendar_date` | DATE | Date of the override |
| `open_time` | TIME, nullable | Exceptional opening time |
| `close_time` | TIME, nullable | Exceptional closing time |
| `is_closed` | BOOLEAN | Complete closure for that date |
| `reason` | VARCHAR | Holiday, maintenance, emergency, etc. |

## `locker`

Represents one physical compartment.

| Column | Type | Purpose |
|---|---|---|
| `id` | UUID, primary key | Globally unique locker ID |
| `location_id` | UUID, foreign key | Owning location |
| `display_number` | VARCHAR | Physical label such as `S1` |
| `size` | ENUM/VARCHAR | `SMALL`, `MEDIUM`, `LARGE`, etc. |
| `length_cm` | INTEGER | Internal usable length |
| `width_cm` | INTEGER | Internal usable width |
| `height_cm` | INTEGER | Internal usable height |
| `status` | ENUM/VARCHAR | `AVAILABLE`, `RESERVED`, `OCCUPIED`, `OUT_OF_SERVICE` |
| `version` | BIGINT | Optimistic-lock version |
| `created_at` | TIMESTAMP | Audit timestamp |
| `updated_at` | TIMESTAMP | Audit timestamp |

The display number only needs to be unique within its location:

```sql
UNIQUE (location_id, display_number)
```

Useful indexes:

```sql
CREATE INDEX idx_locker_availability
ON locker(location_id, status, size);

CREATE INDEX idx_locker_fit
ON locker(location_id, status, length_cm, width_cm, height_cm);
```

Exact dimensions are authoritative for package fit. The size enum is a convenient category and ordering aid.

## `package`

Represents a physical parcel rather than an entire commercial order.

| Column | Type | Purpose |
|---|---|---|
| `id` | UUID, primary key | Package ID |
| `external_order_id` | VARCHAR/UUID | Order-service reference |
| `customer_id` | VARCHAR/UUID | Customer-service reference |
| `direction` | ENUM/VARCHAR | `OUTBOUND` or `RETURN` |
| `length_cm` | INTEGER | Physical length |
| `width_cm` | INTEGER | Physical width |
| `height_cm` | INTEGER | Physical height |
| `status` | ENUM/VARCHAR | Package lifecycle status |
| `created_at` | TIMESTAMP | Creation timestamp |
| `updated_at` | TIMESTAMP | Last update timestamp |

Possible statuses include:

```text
CREATED
LOCKER_RESERVED
IN_LOCKER
PICKED_UP
RETURN_DROPPED_OFF
RETURN_COLLECTED
EXPIRED
REMOVED
CANCELLED
```

One order may be divided into several physical packages. In a microservice architecture, the locker service should normally retain only external customer and order IDs—not duplicate complete customer, order, payment, and refund records.

## `package_item`

This table is necessary if the locker service owns or must query package contents. Otherwise, the packaging or order service can remain the owner.

| Column | Type | Purpose |
|---|---|---|
| `package_id` | UUID, foreign key | Physical package |
| `order_item_id` | UUID/VARCHAR | External order-line reference |
| `quantity` | INTEGER | Quantity placed in this package |

Suggested primary key:

```sql
PRIMARY KEY (package_id, order_item_id)
```

## `locker_assignment`

Records the time-bound association between a package and a locker. This is preferable to placing only `package_id` on the locker because an assignment has its own lifecycle and history.

| Column | Type | Purpose |
|---|---|---|
| `id` | UUID, primary key | Assignment ID |
| `locker_id` | UUID, foreign key | Assigned locker |
| `package_id` | UUID, foreign key | Assigned package |
| `assignment_type` | ENUM/VARCHAR | `DELIVERY` or `RETURN` |
| `status` | ENUM/VARCHAR | Assignment lifecycle state |
| `reserved_at` | TIMESTAMP | Reservation time |
| `reservation_expires_at` | TIMESTAMP | Deadline for abandoned reservation cleanup |
| `placed_at` | TIMESTAMP, nullable | Physical package placement time |
| `pickup_expires_at` | TIMESTAMP, nullable | Customer/courier pickup deadline |
| `completed_at` | TIMESTAMP, nullable | Successful pickup time |
| `cancelled_at` | TIMESTAMP, nullable | Cancellation time |
| `version` | BIGINT | Optimistic-lock version |
| `created_at` | TIMESTAMP | Audit timestamp |
| `updated_at` | TIMESTAMP | Audit timestamp |

Possible statuses:

```text
RESERVED
ACTIVE
COMPLETED
EXPIRED
CANCELLED
```

The critical database constraints are:

```sql
CREATE UNIQUE INDEX uq_active_assignment_per_locker
ON locker_assignment(locker_id)
WHERE status IN ('RESERVED', 'ACTIVE');

CREATE UNIQUE INDEX uq_active_assignment_per_package
ON locker_assignment(package_id)
WHERE status IN ('RESERVED', 'ACTIVE');
```

These constraints prevent double assignment even if application code has a race condition.

For expiry processing:

```sql
CREATE INDEX idx_active_assignment_expiry
ON locker_assignment(status, pickup_expires_at)
WHERE status = 'ACTIVE';
```

## `access_credential`

Persists the current Java `Grant` concept.

| Column | Type | Purpose |
|---|---|---|
| `id` | UUID, primary key | Credential ID |
| `assignment_id` | UUID, foreign key | Assignment being authorized |
| `purpose` | ENUM/VARCHAR | Allowed operation |
| `actor_type` | ENUM/VARCHAR | `CUSTOMER` or `COURIER` |
| `code_hash` | VARCHAR/BINARY | Secure hash of the access code |
| `expires_at` | TIMESTAMP | Expiration time |
| `used_at` | TIMESTAMP, nullable | Successful consumption time |
| `revoked_at` | TIMESTAMP, nullable | Explicit invalidation time |
| `failed_attempts` | INTEGER | Brute-force protection |
| `created_at` | TIMESTAMP | Audit timestamp |

Purposes include:

```text
CUSTOMER_PICKUP
RETURN_DROPOFF
COURIER_COLLECTION
```

The raw access code should be sent to the actor once and not stored. The database stores `hash(code)`. A submitted code is hashed before lookup.

```sql
CREATE UNIQUE INDEX uq_access_credential_code_hash
ON access_credential(code_hash);

CREATE INDEX idx_credential_assignment
ON access_credential(assignment_id);
```

A usable credential must satisfy all of these conditions:

```text
used_at IS NULL
revoked_at IS NULL
expires_at > current time
purpose = requested operation
```

## `locker_event`

Provides an append-only operational and security audit trail.

| Column | Type | Purpose |
|---|---|---|
| `id` | UUID, primary key | Event ID |
| `assignment_id` | UUID, foreign key | Related assignment |
| `locker_id` | UUID, foreign key | Related locker |
| `event_type` | ENUM/VARCHAR | Event name |
| `actor_type` | ENUM/VARCHAR | Customer, courier, system, operator |
| `actor_id` | VARCHAR/UUID, nullable | Actor identity |
| `occurred_at` | TIMESTAMP | Event time |
| `metadata` | JSONB | Hardware response, error data, correlation ID, etc. |

Examples include `LOCKER_RESERVED`, `DOOR_OPENED`, `PACKAGE_PLACED`, `DOOR_CLOSED`, `CODE_REJECTED`, `PACKAGE_COLLECTED`, `ASSIGNMENT_EXPIRED`, and `LOCKER_OUT_OF_SERVICE`.

The event table is history. It does not replace current state in `locker` and `locker_assignment`.

## `notification_outbox`

Supports reliable asynchronous notifications through the transactional outbox pattern.

| Column | Type | Purpose |
|---|---|---|
| `id` | UUID, primary key | Message ID |
| `event_type` | VARCHAR | `PACKAGE_READY`, `RETURN_READY`, etc. |
| `recipient_id` | VARCHAR/UUID | Customer or courier ID |
| `payload` | JSONB | Notification data |
| `status` | ENUM/VARCHAR | `PENDING`, `SENT`, `FAILED` |
| `attempt_count` | INTEGER | Retry count |
| `next_attempt_at` | TIMESTAMP | Next retry time |
| `created_at` | TIMESTAMP | Creation time |
| `sent_at` | TIMESTAMP, nullable | Successful delivery time |

The assignment update and outbox record are committed in one transaction. A background worker delivers the message and retries temporary failures. This prevents a committed delivery from losing its customer notification if the application crashes immediately after committing.

## Atomic locker allocation

The current in-memory implementation uses one `ReentrantLock` per location. This protects only one JVM. A production deployment has multiple instances, so the database must protect allocation.

One PostgreSQL approach is:

```sql
BEGIN;

SELECT id
FROM locker
WHERE location_id = :locationId
  AND status = 'AVAILABLE'
  AND length_cm >= :packageLength
  AND width_cm  >= :packageWidth
  AND height_cm >= :packageHeight
ORDER BY (length_cm * width_cm * height_cm)
FOR UPDATE SKIP LOCKED
LIMIT 1;

UPDATE locker
SET status = 'RESERVED',
    version = version + 1,
    updated_at = now()
WHERE id = :lockerId;

INSERT INTO locker_assignment (
    id, locker_id, package_id, assignment_type, status,
    reserved_at, reservation_expires_at
) VALUES (
    :assignmentId, :lockerId, :packageId, :assignmentType, 'RESERVED',
    now(), now() + interval '30 minutes'
);

COMMIT;
```

`FOR UPDATE` locks the selected row. `SKIP LOCKED` lets concurrent allocators skip a locker already being considered. Ordering by volume implements the smallest-fitting-locker policy.

An alternative is optimistic locking:

```sql
UPDATE locker
SET status = 'RESERVED',
    version = version + 1
WHERE id = :lockerId
  AND status = 'AVAILABLE'
  AND version = :expectedVersion;
```

If zero rows are changed, another request won the race and the caller must retry. Row locking is usually straightforward for this finite-resource allocation problem; optimistic locking works well when conflicts are uncommon.

## Atomic credential consumption and pickup

Credential consumption and locker release belong in one transaction:

```text
BEGIN
  Lock access_credential using code_hash
  Validate purpose, expiry, used_at, and revoked_at
  Lock the associated locker_assignment and locker
  Mark credential as used
  Mark assignment as COMPLETED
  Mark package as PICKED_UP or RETURN_COLLECTED
  Mark locker as AVAILABLE
  Insert locker_event audit record
COMMIT
```

The credential should first be locked:

```sql
SELECT *
FROM access_credential
WHERE code_hash = :codeHash
FOR UPDATE;
```

Then consume it conditionally:

```sql
UPDATE access_credential
SET used_at = now()
WHERE id = :credentialId
  AND used_at IS NULL
  AND revoked_at IS NULL
  AND expires_at > now();
```

Exactly one row must be updated. This prevents two simultaneous requests using the same credential from both succeeding.

## Current versus derived locker status

Locker availability could be derived from assignments:

```text
No active assignment       -> AVAILABLE
Active RESERVED assignment -> RESERVED
Active ACTIVE assignment   -> OCCUPIED
```

Keeping `locker.status` is a useful controlled denormalization because availability is a frequent query. `locker.status` and `locker_assignment.status` must therefore be updated in the same transaction. The assignment remains the lifecycle/history source, while the locker status is an efficient current-state projection.

## Expiration and refunds

When customer pickup expires:

1. Change the assignment to `EXPIRED`.
2. Revoke its credentials.
3. Create a logistics removal task or event.
4. Keep the locker unavailable until the package is physically removed.
5. After removal, update the package and release the locker.
6. Publish `PACKAGE_NOT_COLLECTED` for the Order/Payment service.

The locker service should report facts, not own financial policy. Order or Payment service determines refund eligibility.

## Mapping from the Java implementation

| Java concept | Database representation |
|---|---|
| `LockerLocation` | `locker_location` plus scheduling tables |
| `Locker` | `locker` |
| `Parcel` | `package` |
| `Grant` | `access_credential` |
| `grants` map | Credential table/repository |
| `locks` map | Database row locking or optimistic locking |
| `Locker.parcel` | Active `locker_assignment` |
| `LockerStatus` | `locker.status` |
| `AccessPurpose` | `access_credential.purpose` |
| `LockerService` | Application service coordinating database transactions |

## Concise interview response

> I would use PostgreSQL because locker allocation requires strong transactional consistency. My primary tables are `locker_location`, `locker`, `package`, `locker_assignment`, and `access_credential`.
>
> A package is associated with a locker through `locker_assignment`, which stores reservation, placement, expiry, completion, and assignment type. I keep assignment separate from locker so workflow history is retained rather than repeatedly overwriting `locker.package_id`.
>
> The credential table stores a hash of the one-time code, its purpose, expiry, use time, and revocation status. Pickup, return drop-off, and courier collection use separate scoped credentials.
>
> I enforce one active assignment per locker using a partial unique index. During allocation, I select the smallest fitting available locker using `FOR UPDATE SKIP LOCKED`, update it to reserved, and create the assignment within the same transaction. That prevents multiple service instances from assigning the same physical locker.
>
> I index lockers by location and status, assignments by active expiry, and credentials by code hash. I also maintain an append-only event table for auditing and an outbox table so notifications are reliably delivered after database commits. Order and Payment remain separate services; the locker database stores their external identifiers and publishes expiration-related events.

## Table summary

| Table | Required? | Primary use case |
|---|---|---|
| `locker_location` | Essential | Store physical locker sites, coordinates, time zone, and operational status |
| `location_operating_hours` | Essential | Validate customer and courier access against weekday opening hours |
| `location_schedule_override` | Production extension | Represent holidays, temporary closures, and exceptional hours |
| `locker` | Essential | Store each compartment's dimensions, size category, current state, and owning location |
| `package` | Essential | Store physical parcel dimensions, direction, customer/order references, and lifecycle status |
| `package_item` | Conditional | Record which order items are packed together when this service owns package contents |
| `locker_assignment` | Essential | Atomically associate a package with a locker and track reservation, occupancy, expiry, and completion |
| `access_credential` | Essential | Securely store scoped, expiring, one-time customer and courier access authorization |
| `locker_event` | Strongly recommended | Preserve an immutable security, hardware, and operational audit trail |
| `notification_outbox` | Strongly recommended | Reliably publish customer/courier notifications with retries and crash safety |
