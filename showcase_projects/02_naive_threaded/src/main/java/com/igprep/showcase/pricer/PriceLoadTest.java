package com.igprep.showcase.pricer;

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

        // 3. Warm-up: run submit() for 5 seconds without measuring
        long now_plus_5s = System.nanoTime() + WARMUP_NANO_SECONDS;
        int instrumentIndex = 0;

        while (System.nanoTime() < now_plus_5s) {
            PriceTick tick = new PriceTick("INST-" + instrumentIndex, 100.0, System.nanoTime());
            service.submit(tick);
            instrumentIndex = (instrumentIndex + 1) % N_INSTRUMENTS;
        }

        // reset before timed run
        deliveredCount[0] = 0;  

        long now_plus_30s = System.nanoTime() + SUBMIT_NANO_SECONDS;
        long submitted = 0;
        while(System.nanoTime() < now_plus_30s)
        {
            PriceTick tick = new PriceTick("INST-" + instrumentIndex, 100.0, System.nanoTime());
            service.submit(tick);
            instrumentIndex = (instrumentIndex + 1) % N_INSTRUMENTS;
            submitted++;
        }

        System.out.println("Total submitted: " + submitted);
        System.out.println("Total delivered: " + deliveredCount[0]);
        System.out.println("Throughput: " + (submitted / 30.0) + " updates/second");


    }
}
