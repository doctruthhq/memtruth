package ai.doctruth.cli;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

record ProfileResult(
        String parser,
        int iterations,
        long fileSizeBytes,
        int sectionCount,
        IncludeOutput includeOutput,
        long[] parseLatencyMillis,
        long[] outputLatencyMillis,
        long profiledOutputChars,
        long profiledOutputBytes,
        long heapUsedBeforeBytes,
        long heapUsedAfterBytes) {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    long coldLatencyMillis() {
        return parseLatencyMillis.length == 0 ? -1 : parseLatencyMillis[0];
    }

    long warmAverageLatencyMillis() {
        return warmAverage(parseLatencyMillis);
    }

    long coldOutputLatencyMillis() {
        return outputLatencyMillis.length == 0 ? -1 : outputLatencyMillis[0];
    }

    long warmAverageOutputLatencyMillis() {
        return warmAverage(outputLatencyMillis);
    }

    String toJson() throws CliException {
        try {
            ObjectNode node = MAPPER.createObjectNode();
            node.put("parser", parser);
            node.put("iterations", iterations);
            node.put("fileSizeBytes", fileSizeBytes);
            node.put("sectionCount", sectionCount);
            node.put("includeOutput", includeOutput.id());
            node.set("parseLatencyMillis", MAPPER.valueToTree(parseLatencyMillis));
            node.set("outputLatencyMillis", MAPPER.valueToTree(outputLatencyMillis));
            node.put("coldLatencyMillis", coldLatencyMillis());
            node.put("warmAverageLatencyMillis", warmAverageLatencyMillis());
            node.put("coldOutputLatencyMillis", coldOutputLatencyMillis());
            node.put("warmAverageOutputLatencyMillis", warmAverageOutputLatencyMillis());
            node.put("profiledOutputChars", profiledOutputChars);
            node.put("profiledOutputBytes", profiledOutputBytes);
            node.put("heapUsedBeforeBytes", heapUsedBeforeBytes);
            node.put("heapUsedAfterBytes", heapUsedAfterBytes);
            node.put("heapDeltaBytes", heapUsedAfterBytes - heapUsedBeforeBytes);
            return MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(node);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new CliException("failed to serialize profile JSON", e);
        }
    }

    private static long warmAverage(long[] values) {
        if (values.length <= 1) {
            return -1;
        }
        long total = 0;
        for (int i = 1; i < values.length; i++) {
            total += values[i];
        }
        return total / (values.length - 1);
    }
}
