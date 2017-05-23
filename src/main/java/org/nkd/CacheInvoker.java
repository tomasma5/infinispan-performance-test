package org.nkd;

import org.HdrHistogram.Histogram;
import org.infinispan.Cache;
import org.jgroups.util.Util;

import java.util.concurrent.CountDownLatch;

/**
 * Created by NkD on 22.05.2017.
 */
public class CacheInvoker extends Thread{

    private final Counter counter;
    private final Cache<Long, byte[]> cache;
    private final Config cfg;
    private final CountDownLatch latch;

    private volatile boolean running = true;
    // max recordable value is 80s
    final Histogram getAvg = new Histogram(1, 80_000_000, 3); // us
    final Histogram putAvg = new Histogram(1, 80_000_000, 3); // us

    CacheInvoker(Config cfg, Counter counter, Cache<Long, byte[]> cache, CountDownLatch latch) {
        this.cfg = cfg;
        this.counter = counter;
        this.cache = cache;
        this.latch = latch;
    }

    void cancel() {
        running = false;
    }

    public void run() {
        try {
            latch.await();
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
        while (running) {
            counter.requests.increment();

            // get a random key in range [0 .. num_keys-1]
            Long key = Util.random(cfg.numKeys);

            boolean isThisARead = Util.tossWeightedCoin(cfg.readPercentage);
            byte[] value = null;
            if (!isThisARead) {
                value = Utils.generateValue(cfg.valueSize);
            }
            // try the operation until it is successful
            while (running) {
                try {
                    if (isThisARead) {
                        long start = Util.micros();
                        cache.get(key);
                        long time = Util.micros() - start;
                        getAvg.recordValue(time);
                        counter.reads.increment();
                    } else {
                        long start = Util.micros();
                        cache.put(key, value);
                        long time = Util.micros() - start;
                        putAvg.recordValue(time);
                        counter.writes.increment();
                    }
                    break;
                } catch (Throwable t) {
                    t.printStackTrace();
                }
            }
        }
    }

}
