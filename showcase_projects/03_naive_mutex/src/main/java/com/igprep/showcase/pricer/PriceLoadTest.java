package com.igprep.showcase.pricer;

import java.util.concurrent.CountDownLatch;

public class PriceLoadTest {
    private static int N_INSTRUMENTS = 10_000;
    private static long WARMUP_NANO_SECONDS = 5_000_000_000L;
    private static long SUBMIT_NANO_SECONDS = 30_000_000_000L;

    /*
        // 1. Create PriceUpdateService
        // 2. Register 10,000 listeners (one per instrument)
        // 3. Warm-up: run submit() for 5 seconds without measuring
        // 4. Timed run: run submit() for 30 seconds, count total
        // 5. Print results
     */
    public static void main(String[] args)
    {
        long[] deliveredCount = {0};

        // 1. Create PriceUpdateService
        PriceUpdateService service = new PriceUpdateService();

        // 2. Register 10,000 listeners (one per instrument)
        for (int i = 0; i < N_INSTRUMENTS; i++)
        {
            String instrument = "INST-" + i;
            PriceListener listener = tick -> deliveredCount[0]++;
            service.subscribe(instrument, listener);
        }
       
        // reset before timed run
        deliveredCount[0] = 0;          
        long now_plus_30s = System.nanoTime() + SUBMIT_NANO_SECONDS;

        CountDownLatch startLatch = new CountDownLatch(1);
        long[] submittedCount = new long[4];
        Thread[] producers = new Thread[4];
        
        for (int i = 0; i < 4; i++)
        {
            final int threadId = i;
            producers[i] = new Thread(() -> {
                try
                {
                    System.out.print("❌");
                    startLatch.await();
                    System.out.print("✅");
                    
                    long count = 0;
                    int instrumentIndex = 0;

                    while(System.nanoTime() < now_plus_30s)
                    {
                        PriceTick tick = new PriceTick("INST-" + instrumentIndex, 100.0, System.nanoTime());
                        service.submit(tick);
                        instrumentIndex = (instrumentIndex + 1) % N_INSTRUMENTS;
                        count++;
                    }
                    submittedCount[threadId] = count;
                }
                catch(InterruptedException e) {}
            });
            producers[i].start();
        }

        try {
            Thread.sleep(1000);
            System.out.println("\n🔫");
        } catch (InterruptedException e) {}

        startLatch.countDown();
        for(Thread t : producers) 
        {
            try
            {
                t.join(); 
            } 
            catch(InterruptedException e) {}
        }

        long submitted = submittedCount[0] + submittedCount[1] + submittedCount[2] + submittedCount[3];

        System.out.println("---");

        System.out.printf("Total submitted:     %,d%n", submitted);
        System.out.printf("Total delivered:     %,d%n", deliveredCount[0]);
        System.out.printf("Throughput:          %,.0f updates/sec%n", submitted / 30.0);
        System.out.printf("Total updates:       %,d%n", service.getTotalUpdatesProcessed());
        System.out.printf("Updates lost:        %,d%n", submitted - service.getTotalUpdatesProcessed());
        System.out.printf("Deliveries lost:     %,d%n", submitted - deliveredCount[0]);


        service.shutdown();
    }
}
