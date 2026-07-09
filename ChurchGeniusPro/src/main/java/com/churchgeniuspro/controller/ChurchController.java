package com.churchgeniuspro.controller;

import org.springframework.web.bind.annotation.GetMapping;

public class ChurchController {
	
	@GetMapping("/church")
    public String organization() {
        return "forward:/church.html";
    }

}
