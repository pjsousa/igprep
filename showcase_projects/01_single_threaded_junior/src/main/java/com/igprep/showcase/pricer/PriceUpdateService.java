package com.igprep.showcase.pricer;

public class PriceUpdateService {

    private final PriceCache cache = new PriceCache();
    private final SubscriptionRegistry registry = new SubscriptionRegistry();

    public void subscribe(String instrumentId, PriceListener listener)
    {
        registry.subscribe(instrumentId, listener);
    }

    public void submit(PriceTick tick)
    {
        cache.update(tick);
        for (PriceListener listener : registry.getListeners(tick.instrumentId())) {
            listener.onPrice(tick);
        }
    }
}
