package com.igprep.showcase.pricer;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class PriceUpdateService {

    ExecutorService pool = Executors.newFixedThreadPool(4);

    private final PriceCache cache = new PriceCache();
    private final SubscriptionRegistry registry = new SubscriptionRegistry();

    public void subscribe(String instrumentId, PriceListener listener)
    {
        registry.subscribe(instrumentId, listener);
    }

    public void submit(PriceTick tick)
    {
        pool.execute(() -> {
            _submit(tick);
        });
    }

    private void _submit(PriceTick tick){
        cache.update(tick);
        for (PriceListener listener : registry.getListeners(tick.instrumentId())) {
            listener.onPrice(tick);
        }
    }

    public long getTotalUpdatesProcessed()
    {
        return cache.getTotalUpdatesProcessed();
    }

    public double getLastPriceObserved()
    {
        return cache.getLastPrice();
    }

    public void shutdown()
    {
        pool.shutdown();
    }
}
