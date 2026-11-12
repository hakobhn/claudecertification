package com.example.orchestration;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.logging.Logger;

/** {@link MessageRouter} that keeps every route in a thread-safe list and logs it. */
public final class LoggingMessageRouter implements MessageRouter {

    private static final Logger LOG = Logger.getLogger("coordinator");

    private final List<Route> trace = Collections.synchronizedList(new ArrayList<>());

    @Override
    public void route(String from, String to, String subtopic, String note) {
        trace.add(new Route(from, to, subtopic));
        LOG.info(String.format("%-17s -> %-17s [%s] %s", from, to, subtopic, note));
    }

    @Override
    public List<Route> trace() {
        return trace;
    }
}
