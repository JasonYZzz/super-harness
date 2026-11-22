package io.superharness.worker;

import io.superharness.domain.Problem;
import io.superharness.runengine.*;
import io.superharness.runtimespi.AgentRuntime;

import jakarta.annotation.PreDestroy;

import org.slf4j.*;
import org.springframework.core.env.Environment;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.UUID;
import java.util.concurrent.*;

@Component
class WorkerLoop {
    private static final Logger log = LoggerFactory.getLogger(WorkerLoop.class);
    private final String owner = UUID.randomUUID().toString();
    private final Scheduler scheduler;
    private final RunLifecycle lifecycle;
    private final AgentRuntime runtime;
    private final Artifacts artifacts;
    private final McpCatalog mcp;
    private final io.opentelemetry.api.trace.Tracer tracer;
    private final boolean supportsSandbox;
    private final String profile;
    private final int version, capacity;
    private final ConcurrentMap<UUID, Active> active = new ConcurrentHashMap<>();
    private final ExecutorService threads = Executors.newVirtualThreadPerTaskExecutor();
    private volatile boolean draining;

    private static final class Active {
        final Execution execution;
        volatile AgentRuntime.Handle handle;
        volatile boolean stop;

        Active(Execution execution) {
            this.execution = execution;
        }

        void cancel() {
            stop = true;
            var h = handle;
            if (h != null) h.cancel();
        }
    }

    WorkerLoop(
            Scheduler scheduler,
            RunLifecycle lifecycle,
            AgentRuntime runtime,
            Artifacts artifacts,
            McpCatalog mcp,
            Environment env,
            org.springframework.beans.factory.ObjectProvider<io.opentelemetry.api.OpenTelemetry>
                    telemetry) {
        this.scheduler = scheduler;
        this.lifecycle = lifecycle;
        this.runtime = runtime;
        this.artifacts = artifacts;
        this.mcp = mcp;
        tracer =
                telemetry
                        .getIfAvailable(io.opentelemetry.api.OpenTelemetry::noop)
                        .getTracer("super-harness.worker");
        supportsSandbox = !env.getProperty("SH_SANDBOX_IMAGE", "").isBlank();
        profile = env.getProperty("SH_STORE_PROFILE", "jdbc-mysql");
        version = env.getProperty("SH_STORE_VERSION", Integer.class, 1);
        capacity = env.getProperty("SH_WORKER_CAPACITY", Integer.class, 4);
    }

    @Scheduled(fixedDelayString = "${sh.worker.poll-ms:300}")
    synchronized void poll() {
        if (draining || active.size() >= capacity) return;
        scheduler
                .claim(owner, profile, version, supportsSandbox)
                .ifPresent(
                        e -> {
                            var work = new Active(e);
                            active.put(e.run(), work);
                            threads.submit(() -> execute(work));
                        });
    }

    private void execute(Active work) {
        var e = work.execution;
        var builder =
                tracer.spanBuilder("harness.attempt")
                        .setSpanKind(io.opentelemetry.api.trace.SpanKind.CONSUMER)
                        .setAttribute("harness.run.id", e.run().toString())
                        .setAttribute("harness.attempt", e.attempt());
        if (e.traceparent() != null) {
            var parts = e.traceparent().split("-");
            var parent =
                    io.opentelemetry.api.trace.SpanContext.createFromRemoteParent(
                            parts[1],
                            parts[2],
                            io.opentelemetry.api.trace.TraceFlags.fromHex(parts[3], 0),
                            io.opentelemetry.api.trace.TraceState.getDefault());
            builder.setParent(
                    io.opentelemetry.context.Context.root()
                            .with(io.opentelemetry.api.trace.Span.wrap(parent)));
        }
        var span = builder.startSpan();
        try (var scope = span.makeCurrent()) {
            lifecycle.start(e);
            if (e.turn().mcp() != null
                    && !e.turn().mcp().equals(mcp.get(e.tenant(), e.turn().mcp().id())))
                throw new IllegalStateException("MCP profile changed");
            var meter =
                    new io.superharness.runtimespi.RuntimeMeter() {
                        public UUID reserve(int input, int output) {
                            return reserve(input, output, false);
                        }

                        public UUID reserve(int input, int output, boolean webSearchRequested) {
                            span.addEvent("model.request");
                            return lifecycle.reserve(e, input, output, webSearchRequested);
                        }

                        public void record(UUID call, int input, int output) {
                            span.addEvent("model.usage");
                            lifecycle.record(e, call, input, output);
                        }

                        public void plan(java.util.List<java.util.Map<String, Object>> tasks) {
                            lifecycle.plan(e, tasks);
                        }

                        public void tool(String name) {
                            span.addEvent("tool.call");
                            lifecycle.readTool(e, name);
                        }
                    };
            try (var handle = runtime.prepare(e.turn(), meter)) {
                work.handle = handle;
                if (work.stop) handle.cancel();
                String text = handle.execute(delta -> lifecycle.delta(e, delta));
                if (e.publishArtifact())
                    lifecycle.awaitApproval(e, text, artifacts.stage(e.tenant(), text));
                else lifecycle.finish(e, text, null);
            }
        } catch (Exception failure) {
            span.setStatus(io.opentelemetry.api.trace.StatusCode.ERROR, code(failure));
            log.warn(
                    "Run {} attempt {} stopped ({})",
                    e.run(),
                    e.attempt(),
                    failure.getClass().getSimpleName());
            if (!draining) {
                try {
                    lifecycle.finish(e, null, code(failure));
                } catch (Problem lost) {
                    log.info("Run {} no longer owned", e.run());
                } catch (RuntimeException unavailable) {
                    log.warn("Run {} requires reconciliation", e.run());
                }
            }
        } finally {
            span.end();
            active.remove(e.run(), work);
        }
    }

    private String code(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof Problem problem) return problem.getMessage();
        }
        return "RUNTIME_FAILED";
    }

    @Scheduled(fixedDelay = 5000)
    void heartbeat() {
        for (var work : active.values()) {
            try {
                if (!lifecycle.heartbeat(work.execution)) work.cancel();
            } catch (RuntimeException unavailable) {
                work.cancel();
            }
        }
    }

    @PreDestroy
    synchronized void shutdown() throws InterruptedException {
        draining = true;
        active.values().forEach(Active::cancel);
        threads.shutdown();
        if (!threads.awaitTermination(5, TimeUnit.SECONDS)) threads.shutdownNow();
    }
}
