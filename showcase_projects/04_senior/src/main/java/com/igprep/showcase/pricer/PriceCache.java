package com.igprep.showcase.pricer;

import jdk.internal.vm.annotation.Contended;
import java.util.HashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

public class PriceCache {

    @Contended
    private AtomicLong totalUpdatesProcessed = new AtomicLong(0);
    
    @Contended
    private volatile double lastPrice = 0.0;

    private AtomicReference<HashMap<String, PriceTick>> cache = new AtomicReference<>(new HashMap<>());

    void update(PriceTick tick){
        HashMap<String, PriceTick> snapshot;
        HashMap<String, PriceTick> updated;

        do{
            snapshot = cache.get();
            updated = new HashMap<>(snapshot);
            updated.put(tick.instrumentId, tick);
        } while(!cache.compareAndSet(snapshot, updated));

        
        lastPrice = tick.price;
        totalUpdatesProcessed.incrementAndGet();
    }

    PriceTick getLatest(String instrumentId){
        return cache.get().get(instrumentId);
    }

    long getTotalUpdatesProcessed()
    {
        return totalUpdatesProcessed.get();
    }

    double getLastPrice()
    {
        return lastPrice;
    }
}
