package com.igprep.showcase.pricer;

import java.util.concurrent.CountDownLatch;

public class CorruptionStressTest {
    
    static long TOTAL_UPDATES = 1_000_000L;
    static int THREAD_COUNT  = 4;
    static long UPDATES_PER_THREAD = TOTAL_UPDATES / THREAD_COUNT;
    static boolean done = false;

    public static void main(String[] args)
    {

        PriceUpdateService service = new PriceUpdateService();
        CountDownLatch starLatch = new CountDownLatch(1);

        Thread[] threads = new Thread[THREAD_COUNT];
        

        for (int i = 0; i < THREAD_COUNT; i++)
        {
            threads[i] = new Thread(() -> {
                try
                {
                    starLatch.await();
                    for(int j = 0; j < UPDATES_PER_THREAD; j++)
                    {
                        PriceTick tick = new PriceTick("INST-" + j, 100.0, System.nanoTime());
                        service.submit(tick);
                    }
                } 
                catch(InterruptedException e) {}
            });
            threads[i].start();
        }

        long start = System.nanoTime();
        starLatch.countDown();


        Thread reader = new Thread(() -> {
            long staleReads = 0;
            double prev = 0.0;
            while(!done)
            {
                double current = service.getLastPriceObserved();
                if(current == prev) 
                {
                    staleReads++;
                }
                Thread.yield();
            }
            System.out.println("Stale reads: " + staleReads);
        });
        reader.start();

        for(Thread t : threads)
        {
            try 
            {
                t.join();
            }
            catch(InterruptedException e) {}
        }

        done = true;
        try
        {
            reader.join();
        }
        catch(InterruptedException e) { }
        

        long elapsed = System.nanoTime() - start;
        long expected = TOTAL_UPDATES;
        long actual = service.getTotalUpdatesProcessed();
    

        System.out.println("Expected: " + expected);
        System.out.println("Actual: " + actual);
        System.out.println("Lost: " + (expected - actual));
        System.out.println("Time: " + (elapsed / 1_000_000) + "ms");

        service.shutdown();
    }

}
