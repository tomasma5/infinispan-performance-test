package org.nkd;

import java.io.Serializable;

/**
 * Created by NkD on 22.05.2017.
 */
class Config implements Serializable {

    int numThreads = 50;
    int numKeys = 100000;
    int timeSecs = 20;
    int valueSize = 1000;
    double readPercentage = 0.5;

    @Override
    public String toString() {
        return "numThreads=" + numThreads +
                ", numKeys=" + numKeys +
                ", timeSecs=" + timeSecs +
                ", valueSize=" + valueSize +
                ", readPercentage=" + readPercentage;
    }
}
