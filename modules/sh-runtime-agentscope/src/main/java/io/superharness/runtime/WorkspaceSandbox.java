package io.superharness.runtime;

import io.agentscope.harness.agent.*;
import io.agentscope.harness.agent.filesystem.spec.SandboxFilesystemSpec;
import io.agentscope.harness.agent.sandbox.impl.docker.DockerFilesystemSpec;
import io.agentscope.harness.agent.sandbox.snapshot.SandboxSnapshot;
import io.superharness.runtimespi.AgentRuntime.Turn;

/**
 * Each execution writes a new snapshot; a crashed worker has no path to overwrite the published
 * one.
 */
final class WorkspaceSandbox {
    private final DistributedStore store;
    private final String image;

    WorkspaceSandbox(DistributedStore store, String image) {
        this.store = store;
        this.image = image;
    }

    SandboxSnapshot snapshot(java.util.UUID slot) {
        return store.sandboxSnapshotSpec().build("workspace-" + slot);
    }

    SandboxFilesystemSpec prepare(Turn turn) {
        if (image == null || image.isBlank())
            throw new IllegalStateException("No sandbox image configured");
        var current = snapshot(turn.slot());
        if (turn.previousSlot() != null) {
            var previous = snapshot(turn.previousSlot());
            try (var archive = previous.restore()) {
                current.persist(archive);
            } catch (Exception e) {
                throw new IllegalStateException("Published workspace unavailable", e);
            }
        }
        return new DockerFilesystemSpec()
                .client(new WorkspaceClient())
                .image(image)
                .workspaceRoot("/workspace")
                .network("none")
                .memorySizeBytes(256L * 1024 * 1024)
                .cpuCount(1L)
                .additionalRunArgs(
                        "--read-only",
                        "--cap-drop=ALL",
                        "--security-opt=no-new-privileges",
                        "--pids-limit=64",
                        "--user=65532:65532",
                        "--tmpfs=/workspace:rw,nosuid,nodev,size=8m,uid=65532,gid=65532",
                        "--tmpfs=/tmp:rw,nosuid,nodev,noexec,size=16m")
                .snapshotSpec(ignored -> current)
                .isolationScope(IsolationScope.SESSION)
                .executionGuard(store.sandboxExecutionGuard())
                .workspaceProjectionEnabled(false);
    }

    // docker cp cannot upload to a read-only rootfs. Select the SDK's exec-based file transfer,
    // which runs as the container UID and can write only to the bounded writable mounts.
    static final class WorkspaceClient
            extends io.agentscope.harness.agent.sandbox.impl.docker.DockerSandboxClient {
        @Override
        public io.agentscope.harness.agent.sandbox.Sandbox create(
                io.agentscope.harness.agent.sandbox.WorkspaceSpec workspace,
                io.agentscope.harness.agent.sandbox.snapshot.SandboxSnapshotSpec snapshots,
                io.agentscope.harness.agent.sandbox.impl.docker.DockerSandboxClientOptions
                        options) {
            return resume(super.create(workspace, snapshots, options).getState());
        }

        @Override
        public io.agentscope.harness.agent.sandbox.Sandbox resume(
                io.agentscope.harness.agent.sandbox.SandboxState state) {
            var dockerState =
                    (io.agentscope.harness.agent.sandbox.impl.docker.DockerSandboxState) state;
            return new io.agentscope.harness.agent.sandbox.impl.docker.DockerSandbox(dockerState) {
                @Override
                public boolean supportsFileTransfer(String path) {
                    return false;
                }
            };
        }
    }

    void verify(java.util.UUID slot) {
        try {
            if (!snapshot(slot).isRestorable())
                throw new IllegalStateException("Workspace snapshot missing");
        } catch (Exception e) {
            throw new IllegalStateException("Workspace snapshot unavailable", e);
        }
    }
}
