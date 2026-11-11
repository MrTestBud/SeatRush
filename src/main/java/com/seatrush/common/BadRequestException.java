package com.seatrush.common;

public class BadRequestException extends RuntimeException {
    public BadRequestException(String message) { super(message); }
}
