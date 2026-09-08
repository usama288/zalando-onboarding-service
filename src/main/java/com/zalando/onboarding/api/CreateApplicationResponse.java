package com.zalando.onboarding.api;

import com.zalando.onboarding.flow.FlowDefinition;
import java.util.UUID;

/**
 * @param draftToken the only time this value is ever returned. Only its hash is stored, so
 *                   an applicant who loses it cannot be given it again — they start over
 * @param flow       returned with the token so a client can render step 1 without a second
 *                   call
 */
public record CreateApplicationResponse(UUID applicationId, String draftToken, String resumeUrl,
                                        FlowDefinition flow) {
}
