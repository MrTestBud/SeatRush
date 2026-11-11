package com.seatrush.booking;

/**
 * Two ways to stop two users winning the same seat.
 * PESSIMISTIC: SELECT ... FOR UPDATE; losers queue on the row lock, then see the seat is taken.
 * OPTIMISTIC:  no lock; the @Version check on UPDATE makes all but one writer fail at flush time.
 */
public enum LockStrategy { PESSIMISTIC, OPTIMISTIC }
