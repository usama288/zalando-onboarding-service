package com.zalando.onboarding.flow;

import org.springframework.boot.diagnostics.AbstractFailureAnalyzer;
import org.springframework.boot.diagnostics.FailureAnalysis;

/**
 * Renders flow configuration failures as Boot's own startup banner instead of a stack trace.
 *
 * <p>The point of failing fast is that somebody reads the message. A misconfigured validator
 * name buried under forty frames of bean-factory internals is a message that gets skimmed.
 */
public class FlowConfigurationFailureAnalyzer extends AbstractFailureAnalyzer<FlowConfigurationException> {

    @Override
    protected FailureAnalysis analyze(Throwable rootFailure, FlowConfigurationException cause) {
        return new FailureAnalysis(cause.getMessage(), cause.getAction(), cause);
    }
}
