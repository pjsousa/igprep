package com.igprep.showcase.pricer;

import jdk.internal.vm.annotation.Contended;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReferenceArray;

public class PriceRingBuffer {
    AtomicReferenceArray<PriceTick> buffer = new AtomicReferenceArray<>(524288);

    @Contended
    private AtomicLong producerSequence = new AtomicLong(0);
    
    @Contended
    private volatile long consumerSequence = 0;

    public PriceRingBuffer(int size) {
        buffer = new AtomicReferenceArray<>(size);
        for (int i = 0; i < size; i++) {
            buffer.set(i, new PriceTick());  // pre-fill so get() never returns null
        }
        producerSequence = new AtomicLong(0);
        consumerSequence = 0;
    }


    void tryPublish(PriceTick tick) {
        long seq = producerSequence.getAndIncrement();
        int index = (int) (seq & (buffer.length() - 1));
        
        while (buffer.get(index) != null) {
            Thread.yield();
        }

        buffer.set(index, tick);
    }

    PriceTick poll() {
        // Spin until there's a slot ready for us
        while (producerSequence.get() <= consumerSequence) {
            Thread.yield();
        }
        int index = (int) (consumerSequence & (buffer.length() - 1));
        PriceTick tick = buffer.get(index);
        consumerSequence++;
        buffer.set(index, null);
        return tick;
    }
}
