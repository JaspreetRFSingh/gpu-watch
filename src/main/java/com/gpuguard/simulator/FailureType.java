package com.gpuguard.simulator;

import com.fasterxml.jackson.annotation.JsonValue;

public enum FailureType {
    NONE("none"),
    OOM("cuda_out_of_memory"),
    NCCL_TIMEOUT("nccl_comm_timeout"),
    NODE_DOWN("node_down"),
    NETWORK_FLAP("network_flap"),
    CHECKSUM_MISMATCH("gradient_checksum_mismatch"),
    SLOW_NODE("slow_node_straggler");

    private final String value;

    FailureType(String value) {
        this.value = value;
    }

    @JsonValue
    public String getValue() {
        return value;
    }
}
