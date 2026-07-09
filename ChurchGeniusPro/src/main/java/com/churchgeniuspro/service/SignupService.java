package com.churchgeniuspro.service;

import com.churchgeniuspro.hibernate.SignUp;
import com.churchgeniuspro.model.SignupBO;
import com.churchgeniuspro.repository.LoginRepository;
import com.churchgeniuspro.util.PasswordUtil;
import org.springframework.stereotype.Service;

import java.util.Date;

/**
 * Handles the business logic for creating a new user account from
 * the sign-up form ({@code signup.html}).
 */
@Service
public class SignupService {

    private final LoginRepository loginRepository;

    public SignupService(LoginRepository loginRepository) {
        this.loginRepository = loginRepository;
    }

    public SignUp save(SignupBO bo) {
        if (loginRepository.existsByUsername(bo.getUsername())) {
            throw new IllegalArgumentException(
                    "Username '" + bo.getUsername() + "' is already taken.");
        }

        Date now = new Date();

        SignUp signUp = new SignUp();
        signUp.setClientId(bo.getClientId());
        signUp.setUsername(bo.getUsername());
        signUp.setPassword(PasswordUtil.encode(bo.getPassword()));
        signUp.setActive(true);
        signUp.setDeleted(false);
        signUp.setLocked(false);
        signUp.setCreated(now);
        signUp.setUpdated(now);

        return loginRepository.save(signUp);
    }
}
