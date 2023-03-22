package id.lokal;

import id.lokal.time.TimeGrpc;
import id.lokal.time.TimeOuterClass;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.Bucket4j;
import io.github.bucket4j.Refill;
import io.grpc.CallOptions;
import io.grpc.Channel;
import io.grpc.ClientCall;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.MethodDescriptor;
import io.grpc.StatusRuntimeException;
import net.sourceforge.argparse4j.ArgumentParsers;
import net.sourceforge.argparse4j.inf.ArgumentParser;
import net.sourceforge.argparse4j.inf.ArgumentParserException;
import net.sourceforge.argparse4j.inf.Namespace;
import org.HdrHistogram.Histogram;
import org.HdrHistogram.Recorder;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.LogManager;

import static com.zaxxer.hikari.util.UtilityElf.quietlySleep;

class GrpcGet {
    private static final Logger logger = LogManager.getLogger(GrpcGet.class);

    private static final Recorder serviceTimesRecorder = new Recorder(2);
    private static final Recorder responseTimesRecorder = new Recorder(2);

    private static final Histogram serviceTimes = new Histogram(2);
    private static final Histogram responseTimes = new Histogram(2);

    private static Histogram serviceTimesSnapshot = null;
    private static Histogram responseTimesSnapshot = null;

    private static final AtomicInteger failures = new AtomicInteger();
    private static int currentFailures = 0;

    private static ThreadPoolExecutor executor;

    private static TimeGrpc.TimeBlockingStub stub;

    public static void main(String[] args) {
        ArgumentParser parser = ArgumentParsers.newFor("get-time").build()
                .defaultHelp(true)
                .description("HTTP stress test");

        parser.addArgument("-r", "--rps")
                .type(Integer.class)
                .required(true)
                .help("The RPS during the stress test");

        parser.addArgument("-m", "--mode")
                .choices("brutal", "uniform")
                .setDefault("brutal")
                .help(
                    "brutal: all ops arrive at the beginning of each second\n"
                    + "uniform: ops arrive at fixed rate\n"
                );

        parser.addArgument("-e", "--endpoint")
                .type(String.class)
                .required(true)
                .help("Endpoint to send the requests to");

        parser.addArgument("-c", "--connections")
                .type(Integer.class)
                .setDefault(1)
                .help("Number of connections");

        parser.addArgument("-t", "--thread-pool-size")
                .type(Integer.class)
                .help("The size of the worker thread pool "
                      + "(same as RPS if omitted)");

        parser.addArgument("-w", "--warm-up-duration")
                .type(Integer.class)
                .setDefault(0)
                .help("The duration in seconds for warminig up, when RPS "
                      + "ramps up linearly");

        parser.addArgument("-s", "--stress-duration")
                .type(Integer.class)
                .required(true)
                .help("The duration in seconds for actual stress test");

        Namespace ns = null;
        try {
            ns = parser.parseArgs(args);
        } catch (ArgumentParserException e) {
            parser.handleError(e);
            System.exit(1);
        }

        final int rps = ns.getInt("rps").intValue();
        final String mode = ns.getString("mode");
        final int connections = ns.getInt("connections").intValue();
        final int threadPoolSize = ns.getInt("thread_pool_size") == null ? rps : ns.getInt("thread_pool_size").intValue();
        final int warmUpDuration = ns.getInt("warm_up_duration").intValue();
        final int stressDuration = ns.getInt("stress_duration").intValue();

        final String endpoint = ns.getString("endpoint");

        ManagedChannelBuilder builder = ManagedChannelBuilder.forTarget(endpoint).usePlaintext();
        List<Channel> channels = new ArrayList<Channel>();
        for (int i = 0; i < connections; i++) {
            ManagedChannel channel = builder.build();
            channels.add(channel);
        }
        stub = TimeGrpc.newBlockingStub(new MultiChannel(channels));

        executor = (ThreadPoolExecutor) Executors.newFixedThreadPool(threadPoolSize);

        if (warmUpDuration > 0) {
            warmUp(mode, rps, warmUpDuration);
        }

        stress(mode, rps, stressDuration);
    }

    private static Bandwidth getLimit(final String mode, final int rps) {
        // We use a scheduler to ensure that ops arrive at a fixed RPS
        // throughout the stress test, to avoid coordinated omission.
        //
        // Currently, this stress test supports 2 modes of RPS, i.e.
        // - brutal: Simulate the worst case where all ops arrive at the
        //           beginning of each second. This is the default.
        // - uniform: Simulate the best case where ops arrive at fixed rate.
        if (mode.equals("brutal")) {
            Refill refill = Refill.intervally(rps, Duration.ofSeconds(1));
            return Bandwidth.classic(rps, refill).withInitialTokens(rps);
        }
        else {
            return Bandwidth.simple(rps, Duration.ofSeconds(1)).withInitialTokens(0);
        }
    }

    private static void warmUp(final String mode, final int rps, final int duration) {
        logger.info("Warming up...");

        final ScheduledExecutorService ses = Executors.newSingleThreadScheduledExecutor((Runnable r) -> {
            return new Thread(r, "warm");
        });
        ses.scheduleAtFixedRate(() -> recordMetrics(), 1, 1, TimeUnit.SECONDS);

        final Bucket timerBucket = Bucket4j.builder().addLimit(getLimit("uniform", 1)).build();
        final double rampUpRate = (double) rps / duration;
        int warmUpOps = 0;

        for (int i = 0; i < duration; i++) {
            final int warmUpRps = (int) Math.ceil((i + 1) * rampUpRate);
            final Bucket warmUpBucket = Bucket4j.builder().addLimit(getLimit(mode, warmUpRps)).build();
            warmUpOps += warmUpRps;

            for (int j = 0; j < warmUpRps; j++) {
                warmUpBucket.asScheduler().consumeUninterruptibly(1);
                executor.submit(new GrpcTask());
            }

            timerBucket.asScheduler().consumeUninterruptibly(1);
        }

        while (responseTimes.getTotalCount() + failures.get() < warmUpOps) {
            quietlySleep(100);
        }

        ses.shutdown();

        resetMetrics();

        logger.info("Warmed up, wait awhile first...");
        quietlySleep(1000);
    }

    private static void stress(final String mode, final int rps, final int duration) {
        final ScheduledExecutorService ses = Executors.newSingleThreadScheduledExecutor((Runnable r) -> {
            return new Thread(r, "stat");
        });
        ses.scheduleAtFixedRate(() -> recordMetrics(), 1, 1, TimeUnit.SECONDS);

        final long startTime = System.nanoTime();

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            final long endTime = System.nanoTime();
            final double actualRps = (double) serviceTimes.getTotalCount() / (endTime - startTime) * 1_000_000_000;

            ses.shutdown();
            executor.shutdown();

            logMetrics("(overall service time in ms)", serviceTimes, failures.get());
            logMetrics("(overall response time in ms)", responseTimes, failures.get());
            logger.info("approximate rps: {}", actualRps);
        }, "last"));

        logger.info("Starting for real...");

        final Bucket actualBucket = Bucket4j.builder().addLimit(getLimit(mode, rps)).build();
        final int actualOps = rps * duration;

        for (int i = 0; i < actualOps; i++) {
            actualBucket.asScheduler().consumeUninterruptibly(1);
            executor.submit(new GrpcTask());
        }

        while (responseTimes.getTotalCount() + failures.get() < actualOps) {
            quietlySleep(100);
        }

        System.exit(0);
    }

    private static void resetMetrics() {
        serviceTimesSnapshot = null;
        responseTimesSnapshot = null;

        failures.set(0);
        currentFailures = 0;

        serviceTimes.reset();
        responseTimes.reset();
    }

    private static void recordMetrics() {
        serviceTimesSnapshot = serviceTimesRecorder.getIntervalHistogram(serviceTimesSnapshot);
        responseTimesSnapshot = responseTimesRecorder.getIntervalHistogram(responseTimesSnapshot);

        int prevFailures = currentFailures;
        currentFailures = failures.get();
        int failed = currentFailures - prevFailures;

        logMetrics("(interval service time in ms)", serviceTimesSnapshot, failed);
        logMetrics("(interval response time in ms)", responseTimesSnapshot, failed);

        serviceTimes.add(serviceTimesSnapshot);
        responseTimes.add(responseTimesSnapshot);
    };

    private static void logMetrics(String prefix, Histogram histogram, int failed) {
        logger.printf(
                Level.INFO,
                "%30s count: %5d, min: %8.2f, mean: %8.2f, p99: %8.2f, max: %8.2f, stddev: %6.2f, failed: %5d",
                prefix,
                histogram.getTotalCount(),
                histogram.getMinValue() == Long.MAX_VALUE ? 0 : histogram.getMinValue() / 1_000_000.0,
                histogram.getMean() / 1_000_000.0,
                histogram.getValueAtPercentile(99) / 1_000_000.0,
                histogram.getMaxValue() / 1_000_000.0,
                histogram.getStdDeviation() / 1_000_000.0,
                failed);
    }

    static class GrpcTask implements Runnable {
        private final long createdAt;

        GrpcTask() {
            createdAt = System.nanoTime();
            //logger.debug("task arrives at {}", createdAt);
        }

        public void run() {
            long runningAt = System.nanoTime();

            TimeOuterClass.LocalTimeRequest request = TimeOuterClass.LocalTimeRequest.newBuilder().build();
            //TimeOuterClass.RemoteTimeRequest request = TimeOuterClass.RemoteTimeRequest.newBuilder().build();
            try {
                stub.localTime(request);
                //stub.remoteTime(request);
                long doneAt = System.nanoTime();

                serviceTimesRecorder.recordValue(doneAt - runningAt);
                responseTimesRecorder.recordValue(doneAt - createdAt);
            }
            catch (StatusRuntimeException ex) {
                logger.error("grpc error: {}, status: {}", ex.getMessage(), ex.getStatus());
                logger.debug("grpc error stacktrace:", ex);

                failures.incrementAndGet();
            }
        }
    }

    public static final class MultiChannel extends Channel {
        private final List<Channel> channels;
        private final AtomicInteger pos = new AtomicInteger();

        public MultiChannel(List<Channel> channels) {
          this.channels = new ArrayList<Channel>(channels);
        }

        public <ReqT, RespT> ClientCall<ReqT, RespT> newCall(
            MethodDescriptor<ReqT, RespT> m, CallOptions o) {
          int idx = pos.getAndIncrement() % channels.size();
          return channels.get(idx).newCall(m, o);
        }

        public String authority() {
          return channels.get(0).authority();
        }
    }
}
