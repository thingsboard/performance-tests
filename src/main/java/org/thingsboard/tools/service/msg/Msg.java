package org.thingsboard.tools.service.msg;

import lombok.AllArgsConstructor;
import lombok.Getter;

@AllArgsConstructor
public class Msg {

    @Getter
    private final byte[] data;
    @Getter
    private final boolean triggersAlarm;

    Msg(byte[] data) {
        this.data = data;
        this.triggersAlarm = false;
    }
}
