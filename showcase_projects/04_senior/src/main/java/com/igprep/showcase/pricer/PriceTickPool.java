package com.igprep.showcase.pricer;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

public class PriceTickPool {
    PriceTick[] pool;
    // AtomicInteger cursor;
    AtomicLong cursor;

    public PriceTickPool(int size)
    {
        if(size <= 0 || (size & (size - 1)) != 0) {
            throw new IllegalArgumentException("Size must be a power of 2");
        }

        pool = new PriceTick[size];
        for (int i = 0; i < size; i++)
        {
            pool[i] = new PriceTick();
        }
        cursor = new AtomicLong(0);
    }

    PriceTick acquire()
    {
        // int index = cursor.getAndIncrement() % pool.length;
        long index = cursor.getAndIncrement() & (pool.length - 1);
        return pool[(int)index];
    }
}
