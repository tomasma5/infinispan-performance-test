java -classpath ../target/classes;../target/lib/* -Dproc_id=$$ -server -Xms256m -Xmx1G -Djava.net.preferIPv4Stack=true -Dinfinispan.stagger.delay=5000 -Dcom.sun.management.jmxremote org.nkd.Test