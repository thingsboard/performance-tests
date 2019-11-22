package org.thingsboard.tools.service.msg;

public interface MessageGenerator {

    Msg getNextMessage(String deviceName, boolean shouldTriggerAlarm);

}
