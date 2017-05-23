package org.nkd;

import org.HdrHistogram.Histogram;
import org.infinispan.Cache;
import org.infinispan.context.Flag;
import org.infinispan.manager.DefaultCacheManager;
import org.jgroups.*;
import org.jgroups.conf.ClassConfigurator;
import org.jgroups.jmx.JmxConfigurator;
import org.jgroups.stack.IpAddress;
import org.jgroups.util.*;

import javax.management.MBeanServer;
import java.net.InetAddress;
import java.util.*;
import java.util.concurrent.CountDownLatch;

/**
 * Perf test based on IspnPerfTest - https://github.com/belaban/IspnPerfTest
 *
 * @author Michal Nikodim (michal.nikodim@topmonks.com)
 */
public class Test extends ReceiverAdapter {

    private Config cfg;
    private DefaultCacheManager cacheManager;
    private JChannel controlChannel;
    private Cache<Long, byte[]> replCache;
    private Cache<Long, byte[]> distCache;
    private final Promise<Config> configPromise = new Promise<>();
    private final Promise<Map<Long, Integer>> contentsPromise = new Promise<>();
    private Thread eventLoopThread;
    private Thread testRunner;
    private boolean looping = true;
    private final ResponseCollector<MemberResult> results = new ResponseCollector<>();
    private final Counter counter = new Counter();

    private static final String infoText = "\n\n[1]-Start REPL test [2]-Start DIST test" +
            "\n[a]-Validate REPL cache [b]-Validate DIST cache" +
            "\n[r]-Populate REPL cache [d]-Populate DIST cache" +
            "\n[3]-View [4]-Caches sizes [c]-Clear all caches [v]-Versions" +
            "\n[5]-Threads (%d) [6]-Keys (%,d) [7]-Time (secs) (%d) [8]-Value size (%s) [9]-Read percentage (%.2f)" +
            "\n[q]-Quit [X]-Quit all\n";

    public static void main(String[] args) {
        Utils.jGroupsIPV4Hack();
        ClassConfigurator.add((short) 11000, MemberResult.class);

        String infinispanConfig = "infinispan.xml";

        Test test = null;
        try {
            test = new Test();
            test.init(infinispanConfig);
            Runtime.getRuntime().addShutdownHook(new Thread(test::stopEventThread));
            test.startEventThread();
        } catch (Throwable ex) {
            ex.printStackTrace();
            if (test != null) {
                test.stopEventThread();
            }
        }
    }

    private void init(String infinispanConfig) throws Exception {
        cfg = new Config();

        cacheManager = new DefaultCacheManager(infinispanConfig);
        replCache = cacheManager.getCache("test-repl-cache");
        distCache = cacheManager.getCache("test-dist-cache");

        controlChannel = new JChannel("control-channel.xml");
        controlChannel.setReceiver(this);
        controlChannel.connect("controlChannel");

        try {
            MBeanServer server = Util.getMBeanServer();
            JmxConfigurator.registerChannel(controlChannel, server, "controlChannel", controlChannel.getClusterName(), true);
        } catch (Throwable ex) {
            System.err.println("registering the channel in JMX failed: " + ex);
        }
        if (controlChannel.getView().size() > 1) {
            Address coordinator = controlChannel.getView().getMembers().get(0);
            configPromise.reset(true);
            send(coordinator, Type.GET_CONFIG);
            cfg = configPromise.getResult(5000);
            if (cfg != null) {
                System.out.println("Fetched config from " + coordinator + ": " + cfg);
            } else
                System.err.println("failed to fetch config from " + coordinator);
        }
    }

    public void viewAccepted(View view) {
        System.out.println("** view accepted: " + view);
    }

    private void send(Address destination, Type type, Object... args) throws Exception {
        ByteArrayDataOutputStream out = new ByteArrayDataOutputStream(512);
        out.write((byte) type.ordinal());
        if (args != null && args.length != 0) {
            for (Object arg : args) {
                Util.objectToStream(arg, out);
            }
        }
        controlChannel.send(destination, out.buffer(), 0, out.position());
    }

    public void receive(Message msg) {
        Address sender = msg.src();
        byte[] receiveBuffer = msg.getRawBuffer();
        int offset = msg.getOffset();
        byte ordinalType = receiveBuffer[offset++];
        int len = msg.length() - 1;
        Type type = Type.values()[ordinalType];
        try {
            switch (type) {
                case REPL_TEST:
                    startTestRunner(sender, replCache, Type.REPL_TEST);
                    break;
                case DIST_TEST:
                    startTestRunner(sender, distCache, Type.DIST_TEST);
                    break;
                case GET_CONFIG:
                    send(sender, Type.GET_CONFIG_RESPONSE, cfg);
                    break;
                case GET_CONFIG_RESPONSE:
                    Config config = Util.objectFromStream(new ByteArrayDataInputStream(receiveBuffer, offset, len));
                    configPromise.setResult(config);
                    break;
                case SET_CONFIG:
                    cfg = Util.objectFromStream(new ByteArrayDataInputStream(receiveBuffer, offset, len));
                    System.out.println("Config changed from " + sender + ": " + cfg);
                    break;
                case GET_REPL_CACHE_CONTENT:
                    send(sender, Type.GET_CACHE_CONTENT_RESPONSE, getCacheContent(replCache));
                    break;
                case GET_DIST_CACHE_CONTENT:
                    send(sender, Type.GET_CACHE_CONTENT_RESPONSE, getCacheContent(distCache));
                    break;
                case GET_CACHE_CONTENT_RESPONSE:
                    Map<Long, Integer> cacheContent = Util.objectFromStream(new ByteArrayDataInputStream(receiveBuffer, offset, len));
                    contentsPromise.setResult(cacheContent);
                    break;
                case RESULTS:
                    MemberResult res = Util.objectFromStream(new ByteArrayDataInputStream(receiveBuffer, offset, len));
                    results.add(sender, res);
                    break;
                case QUIT_ALL:
                    System.out.println("-- received quitAll(): shutting down");
                    stopEventThread();
                    break;
                default:
                    throw new IllegalArgumentException(String.format("type %s not known", type));
            }
        } catch (Exception ex) {
            ex.printStackTrace();
        }
    }

    private void startEventThread() {
        eventLoopThread = new Thread("EventLoop") {
            public void run() {
                try {
                    while (looping) {
                        int c = Util.keyPress(String.format(infoText, cfg.numThreads, cfg.numKeys, cfg.timeSecs, Util.printBytes(cfg.valueSize), cfg.readPercentage));
                        switch (c) {
                            case '1': //REPL benchmark
                                startBenchmark(Type.REPL_TEST);
                                break;
                            case '2': //DIST benchamark
                                startBenchmark(Type.DIST_TEST);
                                break;
                            case 'a': //validate REPL caches
                                validateReplCache();
                                break;
                            case 'b': //validate DIST caches
                                validateDistCache();
                                break;
                            case 'r': { //populate REPL caches
                                long start = Util.micros();
                                for (int i = 0; i < cfg.numKeys; i++) {
                                    replCache.put((long) i, Utils.generateValue(cfg.valueSize));
                                }
                                long time = Util.micros() - start;
                                System.out.println("REPL Cache populated - " + (time / 1000000.0) + "s");
                            }
                            break;
                            case 'd': { // populate DIST CACHES
                                long start = Util.micros();
                                for (int i = 0; i < cfg.numKeys; i++) {
                                    distCache.put((long) i, Utils.generateValue(cfg.valueSize));
                                }
                                long time = Util.micros() - start;
                                System.out.println("DIST Cache populated - " + (time / 1000000.0) + "s");
                            }
                            break;
                            case '3': // Print info about cluster
                                System.out.printf("\n-- local: %s\n-- view: %s\n", controlChannel.getAddress(), controlChannel.getView());
                                for (Address member : controlChannel.getView().getMembers()) {
                                    System.out.print("    member " + member);
                                    Object physical = controlChannel.down(new Event(Event.GET_PHYSICAL_ADDRESS, member));
                                    if (physical instanceof IpAddress) {
                                        IpAddress ipAddress = (IpAddress) physical;
                                        InetAddress inetAddress = ipAddress.getIpAddress();
                                        System.out.print(" [" + inetAddress.getHostName() + " - " + inetAddress.getHostAddress() + ":" + ipAddress.getPort() + "]");
                                    }
                                    System.out.println("");
                                }
                                break;
                            case '4': // Print caches sizes
                                System.out.printf("-- replCache size is %d  [content size is %d]\n", replCache.size(), getCacheContent(replCache).size());
                                System.out.printf("-- distCache size is %d  [content size is %d]\n", distCache.size(), getCacheContent(distCache).size());
                                break;
                            case 'c': // Clear all caches
                                replCache.clear();
                                System.out.println("REPL cache evicted");
                                distCache.clear();
                                System.out.println("DIST Cache evicted.");
                                break;
                            case 'v': // Print versions
                                System.out.printf("JGroups: %s, Infinispan: %s\n", org.jgroups.Version.printDescription(), org.infinispan.Version.printVersion());
                                break;
                            case '5':
                                int numThreads = Util.readIntFromStdin("Number of sender threads: ");
                                if (numThreads != cfg.numThreads) {
                                    cfg.numThreads = numThreads;
                                    send(null, Type.SET_CONFIG, cfg);
                                }
                                break;
                            case '6':
                                int numKeys = Util.readIntFromStdin("Number of keys: ");
                                if (numKeys != cfg.numKeys) {
                                    cfg.numKeys = numKeys;
                                    send(null, Type.SET_CONFIG, cfg);
                                }
                                break;
                            case '7':
                                int timeSec = Util.readIntFromStdin("Time (secs): ");
                                if (timeSec != cfg.timeSecs) {
                                    cfg.timeSecs = timeSec;
                                    send(null, Type.SET_CONFIG, cfg);
                                }
                                break;
                            case '8':
                                int messageSize = Util.readIntFromStdin("Message size: ");
                                if (messageSize < Global.LONG_SIZE * 3) {
                                    System.err.println("msg size must be >= " + Global.LONG_SIZE * 3);
                                } else if (messageSize != cfg.valueSize) {
                                    cfg.valueSize = messageSize;
                                    send(null, Type.SET_CONFIG, cfg);
                                }
                                break;
                            case '9':
                                double percentage = Util.readDoubleFromStdin("Read percentage: ");
                                if (percentage < 0 || percentage > 1.0) {
                                    System.err.println("readHistogram percentage must be >= 0 or <= 1.0");
                                } else if (percentage != cfg.readPercentage) {
                                    cfg.readPercentage = percentage;
                                    send(null, Type.SET_CONFIG, cfg);
                                }
                                break;
                            case 'q': //Stop member
                                stopEventThread();
                                return;
                            case 'X': //Stop all members
                                send(null, Type.QUIT_ALL);
                                break;
                            default:
                                break;
                        }
                    }
                } catch (Throwable ex) {
                    ex.printStackTrace();
                    stopEventThread();
                }
            }
        };
        eventLoopThread.setDaemon(true);
        eventLoopThread.start();
    }

    private void stopEventThread() {
        looping = false;
        if (eventLoopThread != null) {
            eventLoopThread.interrupt();
        }
        Util.close(controlChannel);
        cacheManager.stop();
    }

    private void startBenchmark(Type testType) {
        results.reset(controlChannel.getView().getMembers());
        try {
            send(null, testType);
        } catch (Throwable t) {
            System.err.printf("benchmark start failed: %s\n", t);
            return;
        }

        boolean allResults = results.waitForAllResponses(cfg.timeSecs * 1000 * 5);
        if (!allResults) {
            System.err.printf("did not receive all results: missing results from %s\n", results.getMissing());
        }

        long totalReqs = 0, total_time = 0, longest_time = 0;
        Histogram getAvg = null, putAvg = null;
        System.out.println("\n======================= " + testType +" Results: ===========================");
        for (Map.Entry<Address, MemberResult> entry : results.getResults().entrySet()) {
            Address member = entry.getKey();
            MemberResult result = entry.getValue();
            if (result != null) {
                totalReqs += result.numGets + result.numPuts;
                total_time += result.time;
                longest_time = Math.max(longest_time, result.time);
                if (getAvg == null) {
                    getAvg = result.getAvg;
                } else {
                    getAvg.add(result.getAvg);
                }
                if (putAvg == null) {
                    putAvg = result.putAvg;
                } else {
                    putAvg.add(result.putAvg);
                }
            }
            System.out.println(member + ": " + result);
        }
        double reqsSecNode = totalReqs / (total_time / 1000.0);
        double reqsSecCluster = totalReqs / (longest_time / 1000.0);
        double throughput = reqsSecNode * cfg.valueSize;
        double throughputCluster = reqsSecCluster * cfg.valueSize;
        System.out.println(Util.bold(String.format("\nThroughput: %,.0f reqs/sec/node (%s/sec) %,.0f reqs/sec/cluster (%s/sec)\nRoundtrip:  gets %s,\n            puts %s\n\n",
                reqsSecNode, Util.printBytes(throughput), reqsSecCluster, Util.printBytes(throughputCluster),
                Utils.print(getAvg), Utils.print(putAvg))));
    }

    private synchronized void startTestRunner(final Address sender, Cache<Long, byte[]> cache, Type type) {
        if (testRunner != null && testRunner.isAlive())
            System.err.println("test is already running - wait until complete to start a new run");
        else {
            testRunner = new Thread(() -> {
                counter.reset();

                try {
                    System.out.printf("Running %s for %d seconds:\n", type, cfg.timeSecs);
                    // The first call needs to be synchronous with OOB !
                    final CountDownLatch latch = new CountDownLatch(1);
                    CacheInvoker[] invokers = new CacheInvoker[cfg.numThreads];
                    for (int i = 0; i < invokers.length; i++) {
                        invokers[i] = new CacheInvoker(cfg, counter, cache, latch);
                        invokers[i].setName("invoker-" + i);
                        invokers[i].start();
                    }
                    long interval = (long) ((cfg.timeSecs * 1000.0) / 10.0);
                    long start = System.currentTimeMillis();
                    latch.countDown(); // starts all threads
                    for (int i = 1; i <= 10; i++) {
                        Util.sleep(interval);
                        System.out.printf("%d: %s\n", i, Utils.printAverage(start, counter, cfg.valueSize));
                    }
                    for (CacheInvoker invoker : invokers) {
                        invoker.cancel();
                        invoker.join();
                    }
                    long time = System.currentTimeMillis() - start;
                    System.out.println("\ndone (in " + time + " ms)\n");
                    Histogram getAvg = null, putAvg = null;
                    for (CacheInvoker cacheInvoker : invokers) {
                        if (getAvg == null) {
                            getAvg = cacheInvoker.getAvg;
                        } else {
                            getAvg.add(cacheInvoker.getAvg);
                        }
                        if (putAvg == null) {
                            putAvg = cacheInvoker.putAvg;
                        } else {
                            putAvg.add(cacheInvoker.putAvg);
                        }
                    }
                    MemberResult result = new MemberResult(counter.reads.sum(), counter.writes.sum(), time, getAvg, putAvg);
                    send(sender, Type.RESULTS, result);
                } catch (Throwable t) {
                    t.printStackTrace();
                }
            }, "testRunner");
            testRunner.start();
        }
    }

    private Map<Long, Integer> getCacheContent(Cache<Long, byte[]> cache) {
        Map<Long, Integer> cacheContent = new HashMap<>();
        for (Map.Entry<Long, byte[]> entry : cache.getAdvancedCache().withFlags(Flag.CACHE_MODE_LOCAL).entrySet()) {
            cacheContent.put(entry.getKey(), Arrays.hashCode(entry.getValue()));
        }
        return cacheContent;
    }

    private void validateReplCache() {
        View view = controlChannel.getView();
        Map<Long, Integer> content = getCacheContent(replCache);
        System.out.printf("-- Validating replCache content of %s (%d keys):\n", controlChannel.getAddress(), content.size());
        for (Address member : view) {
            if (!member.equals(controlChannel.getAddress())) {
                boolean passed = true;
                try {
                    System.out.println("    .. request REPL cache content from " + member);
                    contentsPromise.reset(false);
                    send(member, Type.GET_REPL_CACHE_CONTENT);
                    Map<Long, Integer> other = contentsPromise.getResult(60000);
                    if (other.size() != content.size()) {
                        System.err.printf("   Member %s has different size %d\n", member, other.size());
                        passed = false;
                    } else {
                        for (Map.Entry<Long, Integer> entry : other.entrySet()) {
                            Integer otherValue = other.get(entry.getKey());
                            if (!Objects.equals(entry.getValue(), otherValue)) {
                                System.err.println("   Member " + member + "has different value for key " + entry.getKey());
                                passed = false;
                            }
                        }
                    }
                    if (passed) {
                        System.out.printf("   Members %s is valid\n", member);
                    }
                } catch (Exception e) {
                    System.err.printf("   failed fetching replCache contents from %s: %s\n", member, e);
                }
            }
        }
    }

    private void validateDistCache() {
        View view = controlChannel.getView();
        Map<Long, Integer> content = getCacheContent(distCache);
        Map<Long, List<Integer>> countMap = new HashMap<>(cfg.numKeys);

        for (Map.Entry<Long, Integer> entry : content.entrySet()) {
            List<Integer> list = new ArrayList<>();
            list.add(entry.getValue());
            countMap.put(entry.getKey(), list);
        }
        int errors = 0;
        for (Address member : view) {
            if (!member.equals(controlChannel.getAddress())) {
                try {
                    System.out.println("    .. request DIST cache content from " + member);
                    contentsPromise.reset(false);
                    send(member, Type.GET_DIST_CACHE_CONTENT);
                    Map<Long, Integer> other = contentsPromise.getResult(60000);
                    for (Map.Entry<Long, Integer> entry : other.entrySet()) {
                        List<Integer> values = countMap.getOrDefault(entry.getKey(), new ArrayList<>());
                        values.add(entry.getValue());
                        if (values.size() > 2) {
                            errors++;
                            System.err.printf("%s - key %d already has more then 2 values\n", member, entry.getKey());
                        }
                        if (!Objects.equals(values.get(0), values.get(values.size() - 1))) {
                            errors++;
                            System.err.printf("%s - key %d has different value\n", member, entry.getKey());
                        }
                    }
                } catch (Exception e) {
                    System.err.printf("   failed fetching distCache content from %s: %s\n", member, e);
                }
            }
        }
        if (errors == 0) {
            System.out.println("   All members are valid");
        } else {
            System.err.printf("    Found %d errors", errors);
        }
    }
}
