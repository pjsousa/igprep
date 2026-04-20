package com.igprep.showcase.pricer;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

public class SubscriptionRegistry {
    private final HashMap<String, List<PriceListener>> subscribers = new HashMap<>();

    void subscribe(String instrumentId, PriceListener listener)
    {
        subscribers
            .computeIfAbsent(instrumentId, k -> new ArrayList<>())
            .add(listener);
    }

    List<PriceListener> getListeners(String instrumentId){
        
        return subscribers.getOrDefault(instrumentId, List.of());
    }
}
