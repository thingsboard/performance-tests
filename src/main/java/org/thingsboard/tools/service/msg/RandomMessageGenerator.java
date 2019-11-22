package org.thingsboard.tools.service.msg;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Random;

@Slf4j
@Service
public class RandomMessageGenerator implements MessageGenerator {

    private final Random random = new Random();
    private static final ObjectMapper mapper = new ObjectMapper();

    @Override
    public Msg getNextMessage(String deviceName, boolean shouldTriggerAlarm) {
        int percent = random.nextInt(100);
        if (percent < 29) {
            return new Msg(getTinyRandomMessage(deviceName, shouldTriggerAlarm), true);
        } else if (percent < 59) {
            return new Msg(getSmallRandomMessage(deviceName));
        } else if (percent < 99) {
            return new Msg(getRandomMessage(deviceName));
        } else {
            return new Msg(getHugeRandomMessage(deviceName));
        }
    }

    private byte[] getTinyRandomMessage(String deviceName, boolean shouldTriggerAlarm) {
        try {
            ObjectNode data = mapper.createObjectNode();
            ArrayNode array = data.putArray(deviceName);
            ObjectNode arrayElement = array.addObject();
            arrayElement.put("ts", System.currentTimeMillis());
            ObjectNode values = arrayElement.putObject("values");
            if (shouldTriggerAlarm) {
                values.put("t1", 100);
            } else {
                values.put("t1", random.nextInt(100));
            }
            return mapper.writeValueAsBytes(data);
        } catch (Exception e) {
            log.warn("Failed to generate message", e);
            throw new RuntimeException(e);
        }
    }

    private byte[] getSmallRandomMessage(String deviceName) {
        try {
            ObjectNode data = mapper.createObjectNode();
            ArrayNode array = data.putArray(deviceName);
            ObjectNode arrayElement = array.addObject();
            arrayElement.put("ts", System.currentTimeMillis());
            ObjectNode values = arrayElement.putObject("values");
            for (int i = 0; i < 20; i++) {
                values.put("t2_" + i, random.nextInt(100));
            }
            return mapper.writeValueAsBytes(data);
        } catch (Exception e) {
            log.warn("Failed to generate message", e);
            throw new RuntimeException(e);
        }
    }

    private byte[] getRandomMessage(String deviceName) {
        try {
            ObjectNode data = mapper.createObjectNode();
            ArrayNode array = data.putArray(deviceName);
            ObjectNode arrayElement = array.addObject();
            arrayElement.put("ts", System.currentTimeMillis());
            ObjectNode values = arrayElement.putObject("values");

            values.put("t3", getValueToRandomMessage(100));

            return mapper.writeValueAsBytes(data);
        } catch (Exception e) {
            log.warn("Failed to generate message", e);
            throw new RuntimeException(e);
        }
    }

    private byte[] getHugeRandomMessage(String deviceName) {
        try {
            ObjectNode data = mapper.createObjectNode();
            ArrayNode array = data.putArray(deviceName);
            ObjectNode arrayElement = array.addObject();
            arrayElement.put("ts", System.currentTimeMillis());
            ObjectNode values = arrayElement.putObject("values");

            values.put("t4", getValueToRandomMessage(1000));

            return mapper.writeValueAsBytes(data);
        } catch (Exception e) {
            log.warn("Failed to generate message", e);
            throw new RuntimeException(e);
        }
    }

    private String getValueToRandomMessage(int n) throws JsonProcessingException {
        ObjectNode values = mapper.createObjectNode();
        for (int i = 0; i < n; i++) {
            values.put("v" + i, random.nextInt(100));
        }
        return mapper.writeValueAsString(values);
    }
}
