package com.chh.autosense.core.session;

import com.chh.autosense.core.security.AuthUser;

/** Optional local initialization after authenticated acceptance and before routing or continuation. */
@FunctionalInterface
public interface AcceptedConversationInitializer {
    void initialize(AuthUser user);
}
