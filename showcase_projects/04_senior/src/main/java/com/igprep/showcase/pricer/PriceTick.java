package com.igprep.showcase.pricer;

public class PriceTick {
    volatile String instrumentId;
    volatile double price;
    volatile long timestamp;
    volatile boolean ready;

    public PriceTick() {
    }
}
