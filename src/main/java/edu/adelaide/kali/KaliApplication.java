package edu.adelaide.kali;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

import edu.adelaide.kali.config.OpenAiProperties;

@EnableConfigurationProperties(OpenAiProperties.class)


@SpringBootApplication
public class KaliApplication {

	public static void main(String[] args) {
		SpringApplication.run(KaliApplication.class, args);
	}

}
