package com.example.arrango;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(properties = "k8s.import.on-startup=false")
class ArrangoApplicationTests {

	@Test
	void contextLoads() {
	}

}
