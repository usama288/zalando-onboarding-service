package com.zalando.onboarding.flow;

/**
 * Thrown while loading or checking flow configuration. Always fatal at startup: a broken
 * flow definition must never reach a running application.
 *
 * <p>Carries its own remedy, so the startup banner can tell an operator what to do about
 * this failure rather than about flow configuration in general.
 */
public class FlowConfigurationException extends RuntimeException {

    private static final String DEFAULT_ACTION =
            "Flow definitions live in src/main/resources/flows/{DE,PL,NL}.yml. "
                    + "The application will not start until configuration and code agree.";

    private final String action;

    public FlowConfigurationException(String message) {
        this(message, DEFAULT_ACTION, null);
    }

    public FlowConfigurationException(String message, String action) {
        this(message, action, null);
    }

    public FlowConfigurationException(String message, String action, Throwable cause) {
        super(message, cause);
        this.action = action;
    }

    /** What the operator should do about it. Rendered as the "Action" of the startup banner. */
    public String getAction() {
        return action;
    }
}
