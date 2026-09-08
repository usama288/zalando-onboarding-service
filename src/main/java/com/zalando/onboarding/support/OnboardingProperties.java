package com.zalando.onboarding.support;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param resumeBaseUrl where a resume link points. The draft token is appended as the last
 *                      path segment. Configured rather than derived from the inbound
 *                      request, because the link is issued for a browser that is not the
 *                      one making this call and a spoofed Host header must not be able to
 *                      redirect a bearer credential
 */
@ConfigurationProperties(prefix = "onboarding")
public record OnboardingProperties(String resumeBaseUrl) {

    public OnboardingProperties {
        if (resumeBaseUrl == null || resumeBaseUrl.isBlank()) {
            throw new IllegalArgumentException("onboarding.resume-base-url is required");
        }
        resumeBaseUrl = resumeBaseUrl.endsWith("/")
                ? resumeBaseUrl.substring(0, resumeBaseUrl.length() - 1)
                : resumeBaseUrl;
    }

    public String resumeUrlFor(String draftToken) {
        return resumeBaseUrl + "/" + draftToken;
    }
}
