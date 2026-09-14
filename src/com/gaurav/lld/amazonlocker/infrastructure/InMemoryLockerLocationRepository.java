package com.gaurav.lld.amazonlocker.infrastructure;

import com.gaurav.lld.amazonlocker.domain.*;
import com.gaurav.lld.amazonlocker.port.*;

import java.util.*;
import java.util.concurrent.*;

public final class InMemoryLockerLocationRepository implements LockerLocationRepository {
    private final Map<String, LockerLocation> data = new ConcurrentHashMap<>();

    public Optional<LockerLocation> find(String id) {
        return Optional.ofNullable(data.get(id));
    }

    public void save(LockerLocation l) {
        data.put(l.id(), l);
    }
}
