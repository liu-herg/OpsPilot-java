package com.opspilot.run;

import com.opspilot.config.JsonCodec;
import com.opspilot.store.RunEventEntity;

import java.time.LocalDateTime;
import java.util.Map;

public record EventView(int sequence, String eventType, Map<String, Object> payload, LocalDateTime createdAt) {

    public static EventView of(RunEventEntity event, JsonCodec json) {
        return new EventView(event.getSequence(), event.getEventType(), json.readMap(event.getPayload()),
                event.getCreatedAt());
    }
}
