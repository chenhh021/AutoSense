package com.chh.autosense.config;

import com.chh.autosense.domain.model.ChatMessage;
import com.chh.autosense.domain.model.Device;
import com.chh.autosense.domain.model.DiagnosticSnapshot;
import com.chh.autosense.domain.model.ProblemReport;
import com.chh.autosense.domain.model.RepairActionLog;
import com.chh.autosense.domain.model.RepairKnowledge;
import com.chh.autosense.domain.model.RepairSession;
import com.chh.autosense.domain.model.User;
import com.mybatisflex.core.FlexGlobalConfig;
import jakarta.annotation.PostConstruct;
import org.springframework.context.annotation.Configuration;

import java.lang.reflect.Field;
import java.time.LocalDateTime;

/**
 * 审计字段填充:insert 时写 createdAt/updatedAt,update 时写 updatedAt。
 * schema 虽有 DEFAULT CURRENT_TIMESTAMP,但 MyBatis-Flex 显式插入 null 列,
 * 需在应用层填充。
 */
@Configuration
public class AuditTimestampConfig {

    private static final Class<?>[] ENTITIES = {
            Device.class, RepairSession.class, ProblemReport.class,
            DiagnosticSnapshot.class, ChatMessage.class, RepairActionLog.class,
            RepairKnowledge.class, User.class
    };

    @PostConstruct
    void registerListeners() {
        for (Class<?> entityClass : ENTITIES) {
            FlexGlobalConfig.getDefaultConfig()
                    .registerInsertListener(AuditTimestampConfig::fillOnInsert, entityClass);
            FlexGlobalConfig.getDefaultConfig()
                    .registerUpdateListener(AuditTimestampConfig::fillOnUpdate, entityClass);
        }
    }

    private static void fillOnInsert(Object entity) {
        LocalDateTime now = LocalDateTime.now();
        setIfPresent(entity, "createdAt", now);
        setIfPresent(entity, "updatedAt", now);
        // user 表为 camelCase 字段(R22):createTime/updateTime/editTime
        setIfPresent(entity, "createTime", now);
        setIfPresent(entity, "updateTime", now);
        setIfPresent(entity, "editTime", now);
    }

    private static void fillOnUpdate(Object entity) {
        LocalDateTime now = LocalDateTime.now();
        setIfPresent(entity, "updatedAt", now);
        setIfPresent(entity, "updateTime", now);
    }

    private static void setIfPresent(Object entity, String fieldName, Object value) {
        try {
            Field field = entity.getClass().getDeclaredField(fieldName);
            field.setAccessible(true);
            field.set(entity, value);
        } catch (NoSuchFieldException ignored) {
            // 该实体无此字段
        } catch (IllegalAccessException e) {
            throw new IllegalStateException("审计字段填充失败: " + fieldName, e);
        }
    }
}
