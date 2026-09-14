package com.chh.autosense.service.knowledge;

import com.chh.autosense.ai.DirectAnswerService;
import com.chh.autosense.ai.EnhancedAnswerService;
import com.chh.autosense.ai.factory.DirectAnswerServiceFactory;
import com.chh.autosense.ai.factory.EnhancedAnswerFactory;
import com.chh.autosense.config.KnowledgeProperties;
import com.chh.autosense.core.security.AuthUser;
import com.chh.autosense.core.session.AcceptedConversationInitializer;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Ticker;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import java.util.Objects;

/** One userId-only cache. Eviction drops a reference, never closes shared models or in-flight calls. */
@Component
@lombok.extern.slf4j.Slf4j
public class UserAiServiceCache implements AcceptedConversationInitializer {
    private final Cache<Long, UserAiServices> cache;
    private final DirectAnswerServiceFactory directFactory;
    private final EnhancedAnswerFactory enhancedFactory;

    @Autowired
    public UserAiServiceCache(KnowledgeProperties properties, DirectAnswerServiceFactory directFactory,
                             EnhancedAnswerFactory enhancedFactory) {
        this(properties, directFactory, enhancedFactory, Ticker.systemTicker());
    }

    public UserAiServiceCache(KnowledgeProperties properties, DirectAnswerServiceFactory directFactory,
                             EnhancedAnswerFactory enhancedFactory, Ticker ticker) {
        this.directFactory = Objects.requireNonNull(directFactory);
        this.enhancedFactory = Objects.requireNonNull(enhancedFactory);
        cache = Caffeine.newBuilder().maximumSize(properties.serviceCache().maximumSize())
                .expireAfterAccess(properties.serviceCache().expireAfterAccess()).ticker(ticker).build();
    }

    @Override public void initialize(AuthUser user) { getOrCreate(user); }

    public UserAiServices getOrCreate(AuthUser user) {
        if (user == null || user.userId() == null || user.userId() <= 0)
            throw new IllegalArgumentException("Authenticated user ID is required");
        return cache.get(user.userId(), ignored -> createServices());
    }

    private UserAiServices createServices() {
        long started = System.nanoTime();
        var services = new UserAiServices(directFactory.directAnswerService(),
                enhancedFactory.problemAnalysisService(), enhancedFactory.enhancedAnswerService());
        log.info("User AI services created: operation=cacheCreate, elapsedMs={}", (System.nanoTime() - started) / 1_000_000);
        return services;
    }

    public void invalidate(Long userId) { cache.invalidate(Objects.requireNonNull(userId)); }
    public long estimatedSize() { return cache.estimatedSize(); }
    public void cleanUp() { cache.cleanUp(); }

    /** Published atomically only after all specialized factory calls succeed. */
    public record UserAiServices(DirectAnswerService direct, EnhancedAnswerService analysis, EnhancedAnswerService enhanced) {
        public UserAiServices {
            Objects.requireNonNull(direct);
            Objects.requireNonNull(analysis);
            Objects.requireNonNull(enhanced);
        }
    }
}
