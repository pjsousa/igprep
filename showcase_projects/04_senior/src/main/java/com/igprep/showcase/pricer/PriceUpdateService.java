package com.igprep.showcase.pricer;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class PriceUpdateService {

    // ExecutorService pool = Executors.newFixedThreadPool(4);

    private final PriceCache cache = new PriceCache();
    private final SubscriptionRegistry registry = new SubscriptionRegistry();
    private final PriceTickPool tickPool = new PriceTickPool(524288);
    private final PriceRingBuffer ringBuffer = new PriceRingBuffer(524288);
    private volatile boolean running = true;

    public PriceUpdateService() {
        startDispatcher();
    }

    void startDispatcher()
    {
        Thread dispatcher = new Thread(() -> {
            while (running) {
                PriceTick tick = ringBuffer.poll();
                _submit(tick);
            }
        });
        dispatcher.setDaemon(true);
        dispatcher.start();
    }

    public void subscribe(String instrumentId, PriceListener listener)
    {
        registry.subscribe(instrumentId, listener);
    }

    public void submit(PriceTick tick)
    {
        ringBuffer.tryPublish(tick);
    }

    public PriceTick acquirePriceTick()
    {
        return tickPool.acquire();
    }

    private void _submit(PriceTick tick){
        cache.update(tick);
        for (PriceListener listener : registry.getListeners(tick.instrumentId)) {
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
        running = false;
    }
}
