package com.seatrush.show;

/**
 * Published (inside a transaction) whenever seats of a show change status or price.
 * It is delivered to the live seat-map stream only after that transaction commits.
 */
public record SeatsChanged(Long showId) {}
