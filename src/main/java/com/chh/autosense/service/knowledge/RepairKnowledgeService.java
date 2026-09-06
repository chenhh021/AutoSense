package com.chh.autosense.service.knowledge;

import com.chh.autosense.config.RagProperties;
import com.chh.autosense.domain.entity.RepairKnowledge;
import com.chh.autosense.mapper.RepairKnowledgeMapper;
import com.mybatisflex.core.query.QueryWrapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

/**
 * 修复知识检索(FR-007,research R4):MySQL 为权威源;
 * embeddings 开启时经 Redis 向量索引做语义检索,否则回退 LIKE 匹配。
 */
@Slf4j
@Service
public class RepairKnowledgeService {

    private final RepairKnowledgeMapper mapper;
    private final RagProperties ragProperties;

    public RepairKnowledgeService(RepairKnowledgeMapper mapper, RagProperties ragProperties) {
        this.mapper = mapper;
        this.ragProperties = ragProperties;
    }

    /**
     * 按设备类型 + 问题摘要检索最匹配的修复知识。
     */
    public Optional<RepairKnowledge> findSolution(String deviceType, String problemSummary) {
        if (ragProperties.embeddingsEnabled()) {
            // Redis Embedding Store 语义检索(research R4;当前默认关闭)。此处保留扩展点,
            // 避免嵌入服务不可用时阻塞主流程。
            log.debug("embeddingsEnabled=true 的语义检索为扩展点,当前回退关键词匹配");
        }
        List<RepairKnowledge> candidates = mapper.selectListByQuery(QueryWrapper.create()
                .where("device_type = ?", deviceType));
        if (candidates.isEmpty()) {
            return Optional.empty();
        }
        String summary = problemSummary == null ? "" : problemSummary;
        return candidates.stream()
                .filter(k -> summary.contains(k.getProblemPattern())
                        || k.getProblemPattern().contains(summary)
                        || containsAnyKeyword(k, summary))
                .findFirst();
        // 无匹配 → Optional.empty,由编排层进入人工引导(FR-010/011)
    }

    private boolean containsAnyKeyword(RepairKnowledge knowledge, String summary) {
        for (String keyword : knowledge.getProblemPattern().split("[,、\\s]+")) {
            if (!keyword.isBlank() && summary.contains(keyword)) {
                return true;
            }
        }
        return false;
    }

    public List<RepairKnowledge> listByDeviceType(String deviceType) {
        return mapper.selectListByQuery(QueryWrapper.create()
                .where("device_type = ?", deviceType));
    }

    /** 按规则 knowledge-ref 精确引用知识条目(R12):ref 与 problem_pattern 关键词互含即命中。 */
    public Optional<RepairKnowledge> findByRef(String deviceType, String knowledgeRef) {
        if (knowledgeRef == null || knowledgeRef.isBlank()) {
            return Optional.empty();
        }
        return listByDeviceType(deviceType).stream()
                .filter(k -> k.getProblemPattern().contains(knowledgeRef)
                        || knowledgeRef.contains(k.getProblemPattern())
                        || containsAnyKeyword(k, knowledgeRef))
                .findFirst();
    }

    /** 白名单动作的用户可读描述:优先知识库方案文本,退化到动作码。 */
    public String actionDescription(String deviceType, String actionCode, String fallback) {
        return listByDeviceType(deviceType).stream()
                .filter(k -> actionCode != null && actionCode.equals(k.getRepairActionCode()))
                .map(RepairKnowledge::getSolutionContent)
                .findFirst()
                .orElse(fallback == null ? actionCode : fallback);
    }
}
