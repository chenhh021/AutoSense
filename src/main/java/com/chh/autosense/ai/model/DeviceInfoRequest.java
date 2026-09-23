package com.chh.autosense.ai.model;
import java.util.List;
public record DeviceInfoRequest(List<String> sns) {
    public DeviceInfoRequest { sns = sns == null ? null : java.util.Collections.unmodifiableList(new java.util.ArrayList<>(sns)); }
}
