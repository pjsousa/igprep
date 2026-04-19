package com.igprep.showcase.pricer;

import java.util.HashMap;

public class PriceCache {

    private HashMap<String, PriceTick> cache = new HashMap<>();

    void update(PriceTick tick){
        cache.put(tick.instrumentId(), tick);
    }

    PriceTick getLatest(String instrumentId){
        return cache.get(instrumentId);
    }
}
