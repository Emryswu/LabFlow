package com.arthur.labops.audit;

import java.util.Locale;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class OperationLogQueryService {

    private final OperationLogRepository operationLogRepository;

    public OperationLogQueryService(OperationLogRepository operationLogRepository) {
        this.operationLogRepository = operationLogRepository;
    }

    @Transactional(readOnly = true)
    public Page<OperationLogResponse> findAll(String actorUsername, String actorRole, String action,
                                              String targetType, Pageable pageable) {
        Specification<OperationLog> specification = (root, query, builder) -> builder.conjunction();
        if (actorUsername != null && !actorUsername.isBlank()) {
            specification = specification.and((root, query, builder) ->
                    builder.equal(root.get("actorUsername"), actorUsername.trim()));
        }
        if (actorRole != null && !actorRole.isBlank()) {
            // Roles are stored as the enum name (STUDENT … ADMIN), or SYSTEM for automatic jobs.
            String role = actorRole.trim().toUpperCase(Locale.ROOT);
            specification = specification.and((root, query, builder) ->
                    builder.equal(root.get("actorRole"), role));
        }
        if (action != null && !action.isBlank()) {
            specification = specification.and((root, query, builder) ->
                    builder.equal(root.get("action"), action.trim()));
        }
        if (targetType != null && !targetType.isBlank()) {
            specification = specification.and((root, query, builder) ->
                    builder.equal(root.get("targetType"), targetType.trim()));
        }
        return operationLogRepository.findAll(specification, pageable).map(OperationLogResponse::from);
    }
}
