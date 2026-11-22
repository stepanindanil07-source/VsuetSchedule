package ru.vsuet.schedule;

import org.springframework.boot.SpringApplication;

public class TestVsuetScheduleApplication {

	public static void main(String[] args) {
		SpringApplication.from(VsuetScheduleApplication::main).with(TestcontainersConfiguration.class).run(args);
	}

}
