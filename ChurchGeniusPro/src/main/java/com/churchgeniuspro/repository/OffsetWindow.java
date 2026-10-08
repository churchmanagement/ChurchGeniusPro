package com.churchgeniuspro.repository;

import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

/**
 * A {@link Pageable} with an arbitrary offset — "{@code limit} rows starting at row
 * {@code offset}" — unlike {@link org.springframework.data.domain.PageRequest}, whose
 * offset is always {@code page * size}. The list services need it to over-fetch one
 * row beyond a page (so they can report {@code hasMore} without a count query),
 * which PageRequest cannot express. Sorting is left to the query's own ORDER BY.
 * Ledger scalability, part A.
 */
public final class OffsetWindow implements Pageable {

    private final long offset;
    private final int  limit;

    public OffsetWindow(long offset, int limit) {
        if (offset < 0) throw new IllegalArgumentException("offset must be >= 0");
        if (limit  < 1) throw new IllegalArgumentException("limit must be >= 1");
        this.offset = offset;
        this.limit  = limit;
    }

    @Override public int  getPageNumber() { return (int) (offset / limit); }
    @Override public int  getPageSize()   { return limit; }
    @Override public long getOffset()     { return offset; }
    @Override public Sort getSort()       { return Sort.unsorted(); }

    @Override public Pageable next()            { return new OffsetWindow(offset + limit, limit); }
    @Override public Pageable previousOrFirst() { return hasPrevious() ? new OffsetWindow(Math.max(0, offset - limit), limit) : this; }
    @Override public Pageable first()           { return new OffsetWindow(0, limit); }
    @Override public Pageable withPage(int pageNumber) { return new OffsetWindow((long) pageNumber * limit, limit); }
    @Override public boolean  hasPrevious()     { return offset > 0; }

    @Override public String toString() { return "OffsetWindow[offset=" + offset + ", limit=" + limit + "]"; }
}
