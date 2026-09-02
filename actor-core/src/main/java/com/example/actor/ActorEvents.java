package com.example.actor;

import jdk.jfr.Category;
import jdk.jfr.Event;
import jdk.jfr.EventType;
import jdk.jfr.Label;
import jdk.jfr.Name;

/** JFR integration that stays allocation-free while recording is disabled. */
public final class ActorEvents {
    private ActorEvents() {
    }

    static MessageEvent beginMessage(String actor, String messageType) {
        if (!MessageEvent.TYPE.isEnabled()) {
            return null;
        }
        MessageEvent event = new MessageEvent();
        event.actor = actor;
        event.messageType = messageType;
        event.begin();
        return event;
    }

    static void endMessage(MessageEvent event, boolean success) {
        if (event == null) {
            return;
        }
        event.success = success;
        event.end();
        event.commit();
    }

    @Name("com.example.actor.Message")
    @Label("Actor Message")
    @Category("Preemptive Actor")
    public static final class MessageEvent extends Event {
        private static final EventType TYPE = EventType.getEventType(MessageEvent.class);

        @Label("Actor")
        String actor;

        @Label("Message Type")
        String messageType;

        @Label("Succeeded")
        boolean success;
    }
}
