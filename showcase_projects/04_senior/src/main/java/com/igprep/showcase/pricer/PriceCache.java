package com.igprep.showcase.pricer;

import java.util.HashMap;

public class PriceCache {

    private long totalUpdatesProcessed = 0;
    private double lastPrice = 0.0;

    private HashMap<String, PriceTick> cache = new HashMap<>();

    synchronized void update(PriceTick tick){
        cache.put(tick.instrumentId(), tick);
        lastPrice = tick.price();
        totalUpdatesProcessed++;
    }

    synchronized PriceTick getLatest(String instrumentId){
        return cache.get(instrumentId);
    }

    synchronized long getTotalUpdatesProcessed()
    {
        return totalUpdatesProcessed;
    }

    synchronized double getLastPrice()
    {
        return lastPrice;
    }
}
