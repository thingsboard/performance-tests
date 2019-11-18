package org.thingsboard.tools.service.msg;

public interface MessageGenerator {

    byte[] getNextMessage(String deviceName);

}
