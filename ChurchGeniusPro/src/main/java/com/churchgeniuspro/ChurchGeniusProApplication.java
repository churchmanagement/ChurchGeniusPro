package com.churchgeniuspro;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
@EntityScan(basePackages = {"com.churchgeniuspro.hibernate", "com.churchgeniuspro.payroll.entity", "com.churchgeniuspro.plaid.entity"})
public class ChurchGeniusProApplication {

	public static void main(String[] args) {
		SpringApplication.run(ChurchGeniusProApplication.class, args);
	}

}
