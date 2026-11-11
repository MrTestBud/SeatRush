package com.seatrush.booking;

import com.seatrush.common.ConflictException;

public class SeatUnavailableException extends ConflictException {
    public SeatUnavailableException() { super("One or more of those seats were just taken. Pick different seats."); }
}
