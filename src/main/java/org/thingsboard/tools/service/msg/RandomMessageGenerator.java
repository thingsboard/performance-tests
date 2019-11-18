package org.thingsboard.tools.service.msg;

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
    public byte[] getNextMessage(String deviceName) {
        int percent = random.nextInt(100);
        if (percent < 49) {
            return getTinyRandomMessage(deviceName);
        } else {
            return getSmallRandomMessage(deviceName);
        }
//        if (percent < 29) {
//            return getTinyRandomMessage(deviceName);
//        } else if (percent < 59) {
//            return getSmallRandomMessage();
//        } else if (percent < 99) {
//            return getRandomMessage();
//        } else {
//            return getHugeRandomMessage();
//        }
    }

    private byte[] getTinyRandomMessage(String deviceName) {
        try {
            ObjectNode data = mapper.createObjectNode();
            ArrayNode array = data.putArray(deviceName);
            ObjectNode arrayElement = array.addObject();
            arrayElement.put("ts", System.currentTimeMillis());
            ObjectNode values = arrayElement.putObject("values");
            values.put("t1", random.nextInt(100));
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

}
