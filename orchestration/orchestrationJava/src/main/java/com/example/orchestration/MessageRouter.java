package com.example.orchestration;

import java.util.List;

/**
 * Centralised routing: the single place that records every message between the
 * coordinator and a subagent. Every route has the coordinator at one end.
 */
public interface MessageRouter {

    /**
     * Records (and logs) one message.
     *
     * @param from     sender: {@code coordinator} or a subagent name
     * @param to       receiver: {@code coordinator} or a subagent name
     * @param subtopic the subtopic the message is about
     * @param note     free text for the log, e.g. the result size
     */
    void route(String from, String to, String subtopic, String note);

    /** Every message recorded so far, in order. */
    List<Route> trace();

    /** One routed message. */
    record Route(String from, String to, String subtopic) {
    }
}
