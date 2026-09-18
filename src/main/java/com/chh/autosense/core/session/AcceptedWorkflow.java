package com.chh.autosense.core.session;

/** Stable admission identity; contains no executable workflow decisions. */
public record AcceptedWorkflow(String requestId, long sessionId, long userId, long messageId,
                               long reportId, int round, String text) { }
