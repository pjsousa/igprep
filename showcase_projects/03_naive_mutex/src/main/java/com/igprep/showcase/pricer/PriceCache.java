package com.igprep.showcase.pricer;

import java.util.HashMap;

public class PriceCache {

    private long totalUpdatesProcessed = 0;
    private double lastPrice = 0.0;

    private HashMap<String, PriceTick> cache = new HashMap<>();

    void update(PriceTick tick){
        cache.put(tick.instrumentId(), tick);
        lastPrice = tick.price();
        totalUpdatesProcessed++;
    }

    PriceTick getLatest(String instrumentId){
        return cache.get(instrumentId);
    }

    long getTotalUpdatesProcessed()
    {
        return totalUpdatesProcessed;
    }

    double getLastPrice()
    {
        return lastPrice;
    }
}
