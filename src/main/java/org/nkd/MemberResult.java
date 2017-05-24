package org.nkd;

import org.HdrHistogram.Histogram;
import org.jgroups.util.Streamable;

import java.io.DataInput;
import java.io.DataOutput;
import java.nio.ByteBuffer;

/**
 * Created by NkD on 22.05.2017.
 */
public class MemberResult implements Streamable {

    long numGets, numPuts;
    long time; // ms
    Histogram getAvg, putAvg;

    public MemberResult() {
        //empty constructor for deserialize
    }

    MemberResult(long numGets, long numPuts, long time, Histogram getAvg, Histogram putAvg) {
        this.numGets = numGets;
        this.numPuts = numPuts;
        this.time = time;
        this.getAvg = getAvg;
        this.putAvg = putAvg;
    }

    public void writeTo(DataOutput out) throws Exception {
        out.writeLong(numGets);
        out.writeLong(numPuts);
        out.writeLong(time);
        writeHistogram(getAvg, out);
        writeHistogram(putAvg, out);
    }

    public void readFrom(DataInput in) throws Exception {
        numGets = in.readLong();
        numPuts = in.readLong();
        time = in.readLong();
        getAvg = readHistogram(in);
        putAvg = readHistogram(in);
    }

    public String toString() {
        long total_reqs = numGets + numPuts;
        double total_reqs_per_sec = total_reqs / (time / 1000.0);
        return String.format("%.2f reqs/sec (%d GETs, %d PUTs), avg RTT (us) = %.2f get, %.2f put",
                total_reqs_per_sec, numGets, numPuts, getAvg.getMean(), putAvg.getMean());
    }

    private static void writeHistogram(Histogram histogram, DataOutput out) throws Exception {
        int size = histogram.getEstimatedFootprintInBytes();
        ByteBuffer buf = ByteBuffer.allocate(size);
        histogram.encodeIntoCompressedByteBuffer(buf, 9);
        out.writeInt(buf.position());
        out.write(buf.array(), 0, buf.position());
    }

    private static Histogram readHistogram(DataInput in) throws Exception {
        int len = in.readInt();
        byte[] array = new byte[len];
        in.readFully(array);
        ByteBuffer buf = ByteBuffer.wrap(array);
        return Histogram.decodeFromCompressedByteBuffer(buf, 0);
    }
}
