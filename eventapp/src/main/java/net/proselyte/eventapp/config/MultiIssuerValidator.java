package net.proselyte.eventapp.config;

import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;

import java.util.List;

public class MultiIssuerValidator implements OAuth2TokenValidator<Jwt> {

    private final List<String> validIssuers;

    public MultiIssuerValidator(List<String> validIssuers) {
        this.validIssuers = validIssuers;
    }

    @Override
    public OAuth2TokenValidatorResult validate(Jwt jwt) {
        String issuer = jwt.getIssuer() != null ? jwt.getIssuer().toString() : null;

        if (issuer != null && validIssuers.contains(issuer)) {
            return OAuth2TokenValidatorResult.success();
        }

        OAuth2Error error = new OAuth2Error(
                "invalid_token",
                "The required issuer is not valid. Expected one of: " + validIssuers + " but got: " + issuer,
                null
        );

        return OAuth2TokenValidatorResult.failure(error);
    }
}

