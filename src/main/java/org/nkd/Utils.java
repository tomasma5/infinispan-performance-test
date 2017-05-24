package org.nkd;

import org.HdrHistogram.Histogram;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jgroups.util.StackType;
import org.jgroups.util.Util;

import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.lang.reflect.Field;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Created by NkD on 22.05.2017.
 */
class Utils {

    static void jGroupsIPV4Hack() {
        try {
            if (!"true".equalsIgnoreCase(System.getProperty("java.net.preferIPv4Stack"))) {
                System.out.println("Prop java.net.preferIPv4Stack is not set. Using Jgroups IPV4 hack");
                Field field = Util.class.getDeclaredField("ip_stack_type");
                field.setAccessible(true);
                field.set(null, StackType.IPv4);
            }
        } catch (Exception e) {
            System.out.println("Jgroups IPV4 hack failed. Please use -Djava.net.preferIPv4Stack=true. " + e);
        }
    }

    static String print(Histogram avg) {
        if (avg == null || avg.getTotalCount() == 0) {
            return "n/a";
        }
        return String.format("min/avg/max = %d/%.2f/%.2f us (%s)", avg.getMinValue(), avg.getMean(), avg.getMaxValueAsDouble(), percentiles(avg));
    }

    static String printAverage(long startTime, Counter counter, int valueSize) {
        long time = System.currentTimeMillis() - startTime;
        long reads = counter.reads.sum();
        long writes = counter.writes.sum();
        double reqsSec = counter.requests.sum() / (time / 1000.0);
        return String.format("%.2f reqs/sec (%s/sec) (%d reads %d writes)", reqsSec, Util.printBytes(reqsSec * valueSize), reads, writes);
    }

    private static final double[] PERCENTILES = {50, 90, 95, 99, 99.9};

    private static String percentiles(Histogram h) {
        StringBuilder sb = new StringBuilder();
        for (double percentile : PERCENTILES) {
            long val = h.getValueAtPercentile(percentile);
            sb.append(String.format("%.2f=%d ", percentile, val));
        }
        sb.append(String.format("[percentile at mean: %.2f]", h.getPercentileAtOrBelowValue((long) h.getMean())));
        return sb.toString();
    }

    static byte[] generateValue(int size) {
        byte[] value = new byte[size];
        ThreadLocalRandom.current().nextBytes(value);
        return value;
    }

    private static RedirectOutputStream outRedirect = new RedirectOutputStream(true);
    private static RedirectOutputStream errRedirect = new RedirectOutputStream(true);

    static void redirectSysOutAndSysErrToLog() {
        System.setOut(new PrintStream(outRedirect));
        System.setErr(new PrintStream(errRedirect));
    }

    static void setMemberForRedirectedStreams(String prefix) {
        outRedirect.member = prefix;
        errRedirect.member = prefix;
    }

    private static class RedirectOutputStream extends OutputStream {
        private final boolean info;
        private Logger logger = LogManager.getLogger(Test.class);
        private StringBuilder buffer = new StringBuilder();
        private String member = "";

        private RedirectOutputStream(boolean info) {
            this.info = info;
        }

        @Override
        public void write(int b) throws IOException {
            if (b == 10 || b == 13) {
                if (buffer.length() != 0) {
                    if (info) {
                        logger.info(member + buffer);
                    } else {
                        logger.error(member + buffer);
                    }
                    buffer.setLength(0);
                }
            } else {
                buffer.append((char) b);
            }
        }
    }
}
