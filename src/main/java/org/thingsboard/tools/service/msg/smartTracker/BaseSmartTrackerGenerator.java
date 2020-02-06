package org.thingsboard.tools.service.msg.smartTracker;

import org.thingsboard.tools.service.msg.BaseMessageGenerator;

import java.text.DecimalFormat;

public abstract class BaseSmartTrackerGenerator extends BaseMessageGenerator {
    protected static DecimalFormat speedFormat = new DecimalFormat("#.#");
    protected static DecimalFormat latLngFormat = new DecimalFormat("#.######");
}
