package com.churchgeniuspro.model;

import java.util.List;

/**
 * One page of a list endpoint plus whether another page exists.
 *
 * <p>Introduced for the Income and Expense list endpoints (ledger scalability,
 * part A): they used to return every row a church ever recorded, which at
 * 800,000 rows is ~100 MB of JSON per page load. The service asks the repository
 * for {@code size + 1} rows and trims, so {@code hasMore} costs no count query.
 */
public record PageSlice<T>(List<T> rows, boolean hasMore) {

    /** Trims an over-fetched list of up to {@code size + 1} items to one page. */
    public static <T> PageSlice<T> of(List<T> overFetched, int size) {
        boolean more = overFetched.size() > size;
        return new PageSlice<>(more ? List.copyOf(overFetched.subList(0, size)) : overFetched, more);
    }
}
