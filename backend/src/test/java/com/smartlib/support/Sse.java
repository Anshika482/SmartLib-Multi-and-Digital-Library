package com.smartlib.support;

import java.util.ArrayList;
import java.util.List;

/** Parses a captured text/event-stream body into (event, data) pairs. */
public final class Sse {
    private Sse() {
    }

    public record Event(String name, String data) {
    }

    public static List<Event> parse(String body) {
        List<Event> events = new ArrayList<>();
        for (String block : body.replace("\r\n", "\n").split("\n\n")) {
            String name = "message";
            StringBuilder data = new StringBuilder();
            for (String line : block.split("\n")) {
                if (line.startsWith("event:")) {
                    name = line.substring(6).trim();
                } else if (line.startsWith("data:")) {
                    if (!data.isEmpty()) {
                        data.append('\n');
                    }
                    data.append(line.substring(5));
                }
            }
            if (!data.isEmpty()) {
                events.add(new Event(name, data.toString()));
            }
        }
        return events;
    }
}
