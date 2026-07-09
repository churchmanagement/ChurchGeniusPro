package com.churchgeniuspro.model;

import lombok.Data;

@Data
public class Church {

    private Integer clientId;
    private String  username;
    private String  password;
    private String  confirmPassword;
}
