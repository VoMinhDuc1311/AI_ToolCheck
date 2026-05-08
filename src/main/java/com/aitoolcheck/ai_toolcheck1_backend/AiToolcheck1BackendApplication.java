package com.aitoolcheck.ai_toolcheck1_backend;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;

@SpringBootApplication
@EnableAsync
public class AiToolcheck1BackendApplication {

	public static void main(String[] args) {
		SpringApplication.run(AiToolcheck1BackendApplication.class, args);
	}

}
