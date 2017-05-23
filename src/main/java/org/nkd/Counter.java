package org.nkd;

import java.util.concurrent.atomic.LongAdder;

/**
 * Created by NkD on 23.05.2017.
 */
public class Counter {

    final LongAdder requests = new LongAdder();
    final LongAdder reads = new LongAdder();
    final LongAdder writes = new LongAdder();

    void reset() {
        requests.reset();
        reads.reset();
        writes.reset();
    }
}
