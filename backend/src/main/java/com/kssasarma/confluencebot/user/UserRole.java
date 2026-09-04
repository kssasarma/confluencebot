package com.kssasarma.confluencebot.user;

import java.util.Comparator;
import java.util.List;
import java.util.Set;

public enum UserRole {
    ADMIN,
    ADMIN_READ_ONLY,
    INGESTOR,
    USER;

    /** A stable, sorted rendering of a role set — the wire format every DTO agrees on. */
    public static List<String> namesOf(Set<UserRole> roles) {
        return roles.stream().map(Enum::name).sorted(Comparator.naturalOrder()).toList();
    }
}
