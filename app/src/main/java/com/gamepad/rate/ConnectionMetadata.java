package com.gamepad.rate;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** Immutable observations. Empty numeric values mean unknown, never an estimated link parameter. */
final class ConnectionMetadata {
    final long observedNs;
    final Map<String, String> values;

    ConnectionMetadata(long observedNs, Map<String, String> values) {
        this.observedNs = observedNs;
        this.values = Collections.unmodifiableMap(new LinkedHashMap<>(values));
    }
}
