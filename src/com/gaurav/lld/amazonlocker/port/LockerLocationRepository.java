package com.gaurav.lld.amazonlocker.port;

import com.gaurav.lld.amazonlocker.domain.LockerLocation;

import java.util.Optional;

public interface LockerLocationRepository {
    Optional<LockerLocation> find(String id);

    void save(LockerLocation location);
}
