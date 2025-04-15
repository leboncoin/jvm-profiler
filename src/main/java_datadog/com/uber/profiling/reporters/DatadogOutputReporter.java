package com.uber.profiling.reporters;

import java.util.*;
import java.util.stream.Collectors;

import org.apache.commons.lang3.StringUtils;

import com.timgroup.statsd.NonBlockingStatsDClient;
import com.timgroup.statsd.StatsDClient;
import com.uber.profiling.Reporter;
import com.uber.profiling.util.AgentLogger;

public class DatadogOutputReporter implements Reporter {
    private static final AgentLogger logger = AgentLogger.getLogger(DatadogOutputReporter.class.getName());

    private StatsDClient statsdClient = null;

    private String prefix = "";
    private String hostname = "localhost";
    private int port = 8125;
    private String[] tags = new String[] {};
    private String[] statics = new String[] {};
    private Set<String> filters = new HashSet();

    @Override
    public void report(String profilerName, Map<String, Object> metrics) {
        ensureStatsdConn();

        List<Map.Entry<String, Number>> individualMetrics = new ArrayList<>();
        Deque<Map.Entry<String, Object>> queue = new LinkedList<>(metrics.entrySet());
        while (!queue.isEmpty()) {
            Map.Entry<String, Object> entry = queue.pollLast();
            String key = entry.getKey();
            Object value = entry.getValue();


            if (!filters.isEmpty()) {
              if (!filters.contains(key)) {
                // If the key is in the filters, we don't want to report it.
                continue;
              }
            }
            if (value instanceof Number) {
                // If it's a number, we can record the metric as is.
                individualMetrics.add(new AbstractMap.SimpleEntry<>(key, (Number)value));
            } else if (value instanceof Map) {
                // If it's a map, we'll want to get individual metrics out of the map.
                ((Map) value).forEach((nestedKey, nestedValue) -> {
                    String newMetricName = key + "." + nestedKey;

                    if (nestedValue instanceof Number) {
                        individualMetrics.add(new AbstractMap.SimpleEntry<>(newMetricName, (Number)nestedValue));
                    } else {
                        queue.push(new AbstractMap.SimpleEntry<>(newMetricName, nestedValue));
                    }
                });
            }
        }

        for (String staticMetric: statics){
            String[] keyValue = staticMetric.split(":");
            individualMetrics.add(new AbstractMap.SimpleEntry<>(keyValue[0], Double.valueOf(keyValue[1])));
        }

        for (Map.Entry<String, Number> entry : individualMetrics) {
            this.statsdClient.gauge(entry.getKey(), entry.getValue().doubleValue());
        }
    }

    @Override
    public void close() {
        synchronized (this) {
            this.statsdClient.close();
            this.statsdClient = null;
        }
    }

    private void ensureStatsdConn() {
        synchronized (this) {
            if (statsdClient == null) {
                statsdClient = new NonBlockingStatsDClient(prefix, hostname, port, tags);
            }
        }
    }

    // properties from yaml file
    @Override
    public void updateArguments(Map<String, List<String>> connectionProperties) {
        for (Map.Entry<String,  List<String>> entry : connectionProperties.entrySet()) {
            String key = entry.getKey();
            List<String> value = entry.getValue();
            if (StringUtils.isNotEmpty(key) && value != null && !value.isEmpty()) {
                String stringValue = value.get(0);
                if (StringUtils.isBlank(stringValue)) {
                    break;
                }

                switch (key) {
                  case "datadog.statsd.prefix":
                    logger.info("Got value for prefix = " + stringValue);
                    this.prefix = stringValue;
                    break;
                  case "datadog.statsd.hostname":
                    logger.info("Got value for hostname = " + stringValue);
                    this.hostname = stringValue;
                    break;
                  case "datadog.statsd.port":
                    logger.info("Got value for port = " + stringValue);
                    this.port = Integer.parseInt(stringValue);
                    break;
                  case "datadog.statsd.tags":
                    logger.info("Got value for tags = " + stringValue);
                    this.tags = stringValue.split(";");
                    break;
                  case "datadog.statsd.statics":
                    logger.info("Got value for static = " + stringValue);
                    this.statics = stringValue.split(";");
                    break;
                  case "datadog.statsd.filters":
                    logger.info("Got value for filters = " + stringValue);
                    this.filters = Arrays.stream(stringValue.split(";")).collect(Collectors.toSet());
                    break;
                }
            }
        }
    }
}

