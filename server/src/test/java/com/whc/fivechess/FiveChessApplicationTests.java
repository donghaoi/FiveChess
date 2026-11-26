package com.whc.fivechess;

import com.whc.fivechess.domain.Step;
import com.whc.fivechess.domain.Steps;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class FiveChessApplicationTests {

    @Test
    void contextLoads() {
        // Spring 容器能正常启动（数据源、MyBatis 等配置可用）
    }

    @Test
    void stepsSerializable() throws Exception {
        // 对局步数需要序列化后存入数据库，这里校验序列化与反序列化结果一致
        List<Step> stepList = new ArrayList<>();
        stepList.add(new Step(1, 1, 1, "white"));
        stepList.add(new Step(2, 2, 2, "black"));
        Steps steps = new Steps(stepList);

        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (ObjectOutputStream out = new ObjectOutputStream(bos)) {
            out.writeObject(steps);
        }

        Object read;
        try (ObjectInputStream in = new ObjectInputStream(new ByteArrayInputStream(bos.toByteArray()))) {
            read = in.readObject();
        }

        assertTrue(read instanceof Steps);
        Steps readSteps = (Steps) read;
        assertEquals(2, readSteps.getSteps().size());
        assertEquals(2, readSteps.getSteps().get(1).getPlayer());
        assertEquals("black", readSteps.getSteps().get(1).getColor());
    }
}