package com.krux.beacon.listener;

import io.netty.handler.codec.http.HttpResponseStatus;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Timer;
import java.util.concurrent.atomic.AtomicBoolean;

import joptsimple.OptionParser;
import joptsimple.OptionSet;
import joptsimple.OptionSpec;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.krux.beacon.listener.kafka.producer.ConnectionTestKafkaProducer;
import com.krux.beacon.listener.kafka.producer.TestKafkaConnTimerTask;
import com.krux.server.http.StdHttpServerHandler;
import com.krux.stdlib.KruxStdLib;

public class TCPStreamListenerServer {

    private static final Logger log = LoggerFactory.getLogger(TCPStreamListenerServer.class.getName());

    public static Map<Integer, List<String>> portToTopicsMap = new HashMap<Integer, List<String>>();
    public static List<Thread> servers = new ArrayList<Thread>();
    
    public static AtomicBoolean running = new AtomicBoolean(false);
    public static AtomicBoolean resetConnTimer = new AtomicBoolean(false);
    private static Timer timer = null;
    public static List<BeaconListener> _listeners = new ArrayList<BeaconListener>();

    public static void main(String[] args) throws InterruptedException {

        // handle a couple custom cli-params
        OptionParser parser = new OptionParser();

        OptionSpec<String> portTopicMappings = parser
                .accepts("port.topic", "The port->topic mappings (ex: 1234:topic1[,topic2])  Specify multiple mappings with multiple cl options.\n  e.g.: --port.topic 1234:topic1[,topic2] --port.topic 4567:topic3[,topic4]")
                .withRequiredArg()
                .ofType(String.class);
        OptionSpec<String> kafkaBrokers = parser
                .accepts("metadata.broker.list", "This is for bootstrapping and the producer will only use it for getting metadata (topics, partitions and replicas). The socket connections for sending the actual data will be established based on the broker information returned in the metadata. The format is host1:port1,host2:port2, and the list can be a subset of brokers or a VIP pointing to a subset of brokers.")
                .withOptionalArg().ofType(String.class).defaultsTo("localhost:9092");
        OptionSpec<Integer> kafkaAckType = parser
                .accepts("request.required.acks",
                        "The type of ack the broker will return to the client.\n  0, which means that the producer never waits for an acknowledgement\n  1, which means that the producer gets an acknowledgement after the leader replica has received the data.\n  -1, which means that the producer gets an acknowledgement after all in-sync replicas have received the data.\nSee https://kafka.apache.org/documentation.html#producerconfigs")
                .withOptionalArg().ofType(Integer.class).defaultsTo(1);
        OptionSpec<String> producerType = parser.accepts("producer.type", "'sync' or 'async'").withOptionalArg()
                .ofType(String.class).defaultsTo("async");
        
        OptionSpec<Integer> kafkaRequestTimeoutMs = parser
                .accepts("request.timeout.ms",
                        "The amount of time the broker will wait trying to meet the request.required.acks requirement before sending back an error to the client.")
                .withOptionalArg()
                .ofType(Integer.class)
                .defaultsTo(10000);
        OptionSpec<String> kafkaCompressionType = parser
                .accepts("compression.codec", 
                "This parameter allows you to specify the compression codec for all data generated by this producer. Valid values are \"none\", \"gzip\" and \"snappy\".")
                .withOptionalArg()
                .ofType(String.class)
                .defaultsTo("none");
        OptionSpec<Integer> messageSendMaxRetries = parser
                .accepts("message.send.max.retries",
                        "This property will cause the producer to automatically retry a failed send request. This property specifies the number of retries when such failures occur. Note that setting a non-zero value here can lead to duplicates in the case of network errors that cause a message to be sent but the acknowledgement to be lost.")
                .withOptionalArg()
                .ofType(Integer.class)
                .defaultsTo(3);
        OptionSpec<Integer> retryBackoffMs = parser
                .accepts("retry.backoff.ms",
                        "Before each retry, the producer refreshes the metadata of relevant topics to see if a new leader has been elected. Since leader election takes a bit of time, this property specifies the amount of time that the producer waits before refreshing the metadata.")
                .withOptionalArg()
                .ofType(Integer.class)
                .defaultsTo(100);
        OptionSpec<Integer> queueBufferingMaxMs = parser
                .accepts("queue.buffering.max.ms",
                        "Maximum time to buffer data when using async mode. For example a setting of 100 will try to batch together 100ms of messages to send at once. This will improve throughput but adds message delivery latency due to the buffering.")
                .withOptionalArg()
                .ofType(Integer.class)
                .defaultsTo(5000);
        OptionSpec<Integer> queueBufferingMaxMessages = parser
                .accepts("queue.buffering.max.messages",
                        "The maximum number of unsent messages that can be queued up the producer when using async mode before either the producer must be blocked or data must be dropped.")
                .withOptionalArg()
                .ofType(Integer.class)
                .defaultsTo(10000);
        OptionSpec<Integer> queueEnqueTimeoutMs = parser
                .accepts("queue.enqueue.timeout.ms",
                        "The amount of time to block before dropping messages when running in async mode and the buffer has reached queue.buffering.max.messages. If set to 0 events will be enqueued immediately or dropped if the queue is full (the producer send call will never block). If set to -1 the producer will block indefinitely and never willingly drop a send.")
                .withOptionalArg()
                .ofType(Integer.class)
                .defaultsTo(-1);
        OptionSpec<Integer> batchNumMessages = parser
                .accepts("batch.num.messages",
                        "The number of messages to send in one batch when using async mode. The producer will wait until either this number of messages are ready to send or queue.buffer.max.ms is reached.")
                .withOptionalArg()
                .ofType(Integer.class)
                .defaultsTo(200);
        OptionSpec<String> clientId = parser
                .accepts("client.id",
                        "The client id is a user-specified string sent in each request to help trace calls. It should logically identify the application making the request.")
                .withOptionalArg()
                .ofType(String.class)
                .defaultsTo("");
        OptionSpec<Integer> sendBufferBytes = parser
                .accepts("send.buffer.bytes",
                        "Socket write buffer size")
                .withOptionalArg()
                .ofType(Integer.class)
                .defaultsTo(100 * 1024);
        OptionSpec<String> heartbeatTopic = parser
                .accepts("heartbeat-topic",
                        "The name of a topic to be used for general connection checking, kafka aliveness, etc.")
                .withOptionalArg()
                .ofType(String.class)
                .defaultsTo("");
       

        // give parser to KruxStdLib so it can add our params to the reserved
        // list
        KruxStdLib.setOptionParser(parser);
        StringBuilder desc = new StringBuilder();
        desc.append( "\nKrux Kafka Stream Listener\n" );
        desc.append( "**************************\n" );
        desc.append( "Will pass incoming eol-delimitted messages on tcp streams to mapped Kafka topics.\n" );
        OptionSet options = KruxStdLib.initialize(desc.toString(), args);

        // parse the configured port -> topic mappings, put in global hashmap
        Map<OptionSpec<?>, List<?>> optionMap = options.asMap();
        List<?> portTopicMap = optionMap.get(portTopicMappings);

        for (Object mapping : portTopicMap) {
            String mappingString = (String) mapping;
            String[] parts = mappingString.split(":");
            Integer port = Integer.parseInt(parts[0]);
            String[] topics = parts[1].split(",");

            List<String> topicList = new ArrayList<String>();
            for (String topic : topics) {
                topicList.add(topic);
            }
            portToTopicsMap.put(port, topicList);
        }

        // these are picked up by the KafkaProducer class
        System.setProperty("metadata.broker.list", (String) optionMap.get(kafkaBrokers).get(0));
        System.setProperty("request.required.acks", String.valueOf((Integer) optionMap.get(kafkaAckType).get(0)));
        System.setProperty("producer.type", (String) optionMap.get(producerType).get(0));
        
        System.setProperty("request.timeout.ms", String.valueOf((Integer) optionMap.get(kafkaRequestTimeoutMs).get(0)));
        System.setProperty("compression.codec", (String)  optionMap.get(kafkaCompressionType).get(0));
        System.setProperty("message.send.max.retries", String.valueOf((Integer) optionMap.get(messageSendMaxRetries).get(0)));
        System.setProperty("retry.backoff.ms", String.valueOf((Integer) optionMap.get(retryBackoffMs).get(0)));
        System.setProperty("queue.buffering.max.ms", String.valueOf((Integer) optionMap.get(queueBufferingMaxMs).get(0)));
        System.setProperty("queue.buffering.max.messages", String.valueOf((Integer) optionMap.get(queueBufferingMaxMessages).get(0)));
        System.setProperty("queue.enqueue.timeout.ms", String.valueOf((Integer) optionMap.get(queueEnqueTimeoutMs).get(0)));
        System.setProperty("batch.num.messages", String.valueOf((Integer) optionMap.get(batchNumMessages).get(0)));
        System.setProperty("client.id", (String)  optionMap.get(clientId).get(0));
        System.setProperty("send.buffer.bytes", String.valueOf((Integer) optionMap.get(sendBufferBytes).get(0)));
        
        //start a timer that will check every N seconds to see if test messages can be sent to kafka
        // if so, then start our listeners
        String testTopic = options.valueOf(heartbeatTopic);
        try {
            if ( testTopic != null && !testTopic.trim().equals("") ) {
                ConnectionTestKafkaProducer.sendTest(options.valueOf(heartbeatTopic));
                startListeners( testTopic );
            } else {
                startListeners( testTopic );
            }
        } catch ( Exception e ) {
            StdHttpServerHandler.setStatusCodeAndMessage( HttpResponseStatus.INTERNAL_SERVER_ERROR, "Cannot start listeners: " + e.getMessage() );
            System.err.println( "Cannot start listeners." );
            log.error( "Cannot start listeners", e );
            startConnChecker(testTopic);
        }
        
        //Jos doesn't want this thing to close even if no port mappings are specified. Hmm.
        // for now, just hang indefinitely
        if ( servers.size() <= 1 ) {
            System.err.println( "No listeners started.  See previous errors." );
            StdHttpServerHandler.setStatusCodeAndMessage( HttpResponseStatus.INTERNAL_SERVER_ERROR, "No listeners started." );
            do {
                Thread.sleep( 1000 );
            } while ( true );
        }

        System.out.println("Closed.");

    }

    public static void startListeners( String testTopic ) {
        // ok, mappings and properties handled. Now, start tcp server on each
        // port

        if ( !TCPStreamListenerServer.running.get() ) {
            _listeners = new ArrayList<BeaconListener>();
            servers.clear();
            
            for (Map.Entry<Integer, List<String>> entry : portToTopicsMap.entrySet()) {
                StringBuilder sb = new StringBuilder();
                for (String topic : entry.getValue()) {
                    sb.append(topic);
                    sb.append(", ");
                }
                log.info("Starting listener on port " + entry.getKey() + " for topics " + sb.toString());
                BeaconListener listener = new BeaconListener(entry.getKey(), entry.getValue());
                _listeners.add( listener );
                Thread t = new Thread(listener);
                servers.add(t);
                t.start();
            }
            
            TCPStreamListenerServer.running.set( true );
            
            startConnChecker(testTopic);
            StdHttpServerHandler.resetStatusCodeAndMessageOK();
            
            for (Thread t : servers) {
                try {
                    // unless something goes horribly wrong and doesn't get caught
                    // somewhere downstream, we'll never make it past the following
                    // line
                    t.join();
                } catch (InterruptedException e) {
                    log.error("Error after starting server", e);
                }
            }
        }
    }

    private static void startConnChecker(String testTopic) {
        //start a timer that will check if everything's kosher
        log.info( "Trying to start the conn checker" );
        if ( testTopic != null && !testTopic.trim().equals("") ) {
            if ( timer == null ) {
                log.info( "testTopic is not null but timer was null" );
                timer = new Timer();
                TestKafkaConnTimerTask tt = new TestKafkaConnTimerTask( testTopic );
                timer.schedule( tt, 5000, 1000 );
            } else {
                log.info( "testTopic is not null AND timer was not null" );
                if ( resetConnTimer.get() ) {
                    timer.cancel();
                    timer = new Timer();
                    TestKafkaConnTimerTask tt = new TestKafkaConnTimerTask( testTopic );
                    timer.schedule( tt, 5000, 1000 );
                    resetConnTimer.set( false );
                }
            }
        } else {
            log.info( "testTopic is null" );
        }
    }

}
