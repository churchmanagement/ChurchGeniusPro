package com.churchgeniuspro.service;

import com.churchgeniuspro.hibernate.SignUp;
import com.churchgeniuspro.repository.PersonalAccessTokenRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

/**
 * What a new password does to everything already issued for that login (2026-11-11, App Store security review).
 *
 * <ol>
 *   <li>{@link #beforeSave} — call after setting the new password hash, before saving the row: raises
 *       {@code credential_version} (every open session on every browser, device and instance is refused on its next
 *       request — see {@code util.CredentialSessions}) and clears the legacy remember-me column.</li>
 *   <li>{@link #afterSave} — revokes every remember-me / mobile device token and every personal access token
 *       (external AI tools) of the login, so none of them can be used to get back in.</li>
 * </ol>
 */
@Service
public class CredentialChangeService {

    private static final Logger log = LoggerFactory.getLogger(CredentialChangeService.class);

    @Autowired(required = false) private RememberMeService rememberMe;
    @Autowired(required = false) private PersonalAccessTokenRepository accessTokens;

    public CredentialChangeService() {}

    /** Test seam. */
    public CredentialChangeService(RememberMeService rememberMe, PersonalAccessTokenRepository accessTokens) {
        this.rememberMe = rememberMe; this.accessTokens = accessTokens;
    }

    public void beforeSave(SignUp login) {
        if (login == null) return;
        login.markCredentialChanged();
        login.setRemember(null);
    }

    /** @return how many devices and tokens were revoked. Joins the caller's transaction (the revokes are bulk updates). */
    @org.springframework.transaction.annotation.Transactional
    public int afterSave(SignUp login, String reason) {
        if (login == null) return 0;
        int devices = 0, tokens = 0;
        if (rememberMe != null && login.getId() != null) devices = rememberMe.revokeAllFor(login.getId());
        if (accessTokens != null && login.getUsername() != null)
            tokens = accessTokens.revokeAllForUsername(login.getUsername(), "password-" + reason, LocalDateTime.now());
        log.info("Password {} for login id={}: credential version now {}, revoked {} remembered device(s) and {} access token(s)",
                reason, login.getId(), login.getCredentialVersion(), devices, tokens);
        return devices + tokens;
    }
}
