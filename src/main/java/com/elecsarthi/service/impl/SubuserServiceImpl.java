package com.elecsarthi.service.impl;

import java.time.Clock;
import com.elecsarthi.dto.request.CreateBoothAssignmentRequest;
import com.elecsarthi.dto.request.CreateSubuserRequest;
import com.elecsarthi.dto.request.RevokeBoothAssignmentRequest;
import com.elecsarthi.dto.response.BoothAssignmentResponse;
import com.elecsarthi.dto.response.SubuserDetailResponse;
import com.elecsarthi.entity.AppUser;
import com.elecsarthi.entity.BoothAssignment;
import com.elecsarthi.entity.ElectionType;
import com.elecsarthi.entity.Entitlement;
import com.elecsarthi.enums.AuditOutcome;
import com.elecsarthi.enums.BoothAssignmentStatus;
import com.elecsarthi.enums.EntitlementStatus;
import com.elecsarthi.enums.Role;
import com.elecsarthi.enums.UserStatus;
import com.elecsarthi.exception.common.ApiException;
import com.elecsarthi.exception.common.BadRequestException;
import com.elecsarthi.exception.common.ConflictException;
import com.elecsarthi.exception.common.ForbiddenException;
import com.elecsarthi.exception.common.ResourceNotFoundException;
import com.elecsarthi.exception.errorcode.ErrorCode;
import com.elecsarthi.repository.AppUserRepository;
import com.elecsarthi.repository.BoothAssignmentRepository;
import com.elecsarthi.repository.ElectionTypeRepository;
import com.elecsarthi.repository.EntitlementRepository;
import com.elecsarthi.security.authorization.VoterScopeAuthorizer;
import com.elecsarthi.service.AuditService;
import com.elecsarthi.service.SubuserService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class SubuserServiceImpl implements SubuserService {

    private final AppUserRepository userRepository;
    private final BoothAssignmentRepository boothAssignmentRepository;
    private final EntitlementRepository entitlementRepository;
    private final ElectionTypeRepository electionTypeRepository;
    private final PasswordEncoder passwordEncoder;
    private final AuditService auditService;
    private final JdbcTemplate jdbcTemplate;
    private final VoterScopeAuthorizer voterScopeAuthorizer;
    private final Clock clock;

    @Override
    @Transactional
    public SubuserDetailResponse createSubuser(Long customerUserId, Role callerRole, Long customerAccountId, CreateSubuserRequest request) {
        if (callerRole != Role.CUSTOMER) {
            throw new ForbiddenException("Only Customer accounts can create Subusers");
        }

        if (userRepository.existsByUsername(request.getUsername().trim())) {
            throw new ConflictException("Username is already taken");
        }

        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);

        AppUser subuser = AppUser.builder()
                .username(request.getUsername().trim())
                .fullName(request.getFullName().trim())
                .passwordHash(passwordEncoder.encode(request.getPassword()))
                .role(Role.SUBUSER)
                .customerAccountId(customerAccountId)
                .status(UserStatus.ACTIVE)
                .createdByUserId(customerUserId)
                .createdAt(now)
                .build();

        subuser = userRepository.save(subuser);

        auditService.logDetails(customerUserId, customerAccountId, null, null,
                "SUBUSER_CREATED", AuditOutcome.SUCCESS, "app_user", subuser.getId().toString(),
                AuditService.details("username", subuser.getUsername()), null);

        return SubuserDetailResponse.builder()
                .id(subuser.getId())
                .username(subuser.getUsername())
                .fullName(subuser.getFullName())
                .role(subuser.getRole())
                .customerAccountId(customerAccountId)
                .status(subuser.getStatus())
                .activeBoothAssignments(Collections.emptyList())
                .createdAt(subuser.getCreatedAt())
                .build();
    }

    @Override
    @Transactional
    public BoothAssignmentResponse assignBooth(Long customerUserId, Role callerRole, Long customerAccountId, Long subuserId, CreateBoothAssignmentRequest request) {
        if (callerRole != Role.CUSTOMER) {
            throw new ForbiddenException("Only Customer accounts can assign booths to Subusers");
        }

        AppUser subuser = userRepository.findById(subuserId)
                .orElseThrow(() -> new ResourceNotFoundException("Subuser not found"));

        if (subuser.getRole() != Role.SUBUSER || !customerAccountId.equals(subuser.getCustomerAccountId())) {
            throw new ResourceNotFoundException("Subuser not found");
        }

        if (subuser.getStatus() != UserStatus.ACTIVE) {
            throw new BadRequestException("Subuser account is not active");
        }

        Entitlement ent = entitlementRepository.requireById(request.getEntitlementId());

        if (!customerAccountId.equals(ent.getCustomerAccountId())) {
            throw new ResourceNotFoundException("Entitlement not found");
        }
        if (ent.getStatus() != EntitlementStatus.ACTIVE) {
            throw new ForbiddenException("Entitlement is not active");
        }

        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        if (now.isBefore(ent.getValidFrom()) || !now.isBefore(ent.getValidUntil())) {
            throw new BadRequestException("Entitlement is expired or not yet active");
        }

        Instant validFrom = now;
        Instant validUntil = validFrom.plus(request.getDurationDays(), ChronoUnit.DAYS);

        if (validUntil.isAfter(ent.getValidUntil())) {
            throw new BadRequestException("Booth assignment validity (" + validUntil + ") cannot exceed entitlement expiration (" + ent.getValidUntil() + ")");
        }

        // Verify booth exists within entitlement area
        ElectionType electionType = electionTypeRepository.requireById(ent.getElectionTypeId());
        String boothColumn = electionType.getBoothColumn();

        // Same fail-closed scope as every voter read: dataset + whitelisted FILTER criteria. Throws 403 when
        // the criteria are missing, malformed, empty or use a non-whitelisted column.
        VoterScopeAuthorizer.VoterScope scope = voterScopeAuthorizer.buildVoterScope(
                ent, electionType, customerUserId, Role.CUSTOMER, "v");
        List<Object> checkParams = new ArrayList<>(scope.params());
        checkParams.add(request.getBoothValue());
        String checkSql = "SELECT 1 FROM voter v WHERE " + scope.whereClause()
                + " AND v." + boothColumn + " = ? LIMIT 1";

        List<Integer> matches = jdbcTemplate.query(checkSql,(rs, rowNum) -> rs.getInt(1), checkParams.toArray());
        if (matches.isEmpty()) {
            throw new BadRequestException("Booth " + request.getBoothValue() + " does not exist within the area of this entitlement");
        }

        // Verify slot and booth conflicts
        List<BoothAssignment> existingAssignments = boothAssignmentRepository.findActiveAssignmentsForSubuserAndEntitlement(
                subuserId, ent.getId(), now);

        for (BoothAssignment ba : existingAssignments) {
            if (ba.getSlot() == request.getSlot()) {
                throw new ApiException(ErrorCode.SUBUSER_BOOTH_LIMIT_EXCEEDED,
                        "Slot " + request.getSlot() + " is already occupied by an active booth assignment",
                        HttpStatus.BAD_REQUEST);
            }
            if (ba.getBoothValue() == request.getBoothValue()) {
                throw new ApiException(ErrorCode.CONFLICT,
                        "Subuser is already actively assigned to booth " + request.getBoothValue(),
                        HttpStatus.BAD_REQUEST);
            }
        }

        BoothAssignment assignment = BoothAssignment.builder()
                .customerAccountId(customerAccountId)
                .subuserId(subuserId)
                .entitlementId(ent.getId())
                .boothValue(request.getBoothValue())
                .slot(request.getSlot())
                .status(BoothAssignmentStatus.ACTIVE)
                .validFrom(validFrom)
                .validUntil(validUntil)
                .assignedByUserId(customerUserId)
                .createdAt(now)
                .build();

        assignment = boothAssignmentRepository.save(assignment);

        auditService.logDetails(customerUserId, customerAccountId, ent.getDatasetId(), null,
                "BOOTH_ASSIGNED", AuditOutcome.SUCCESS, "booth_assignment", assignment.getId().toString(),
                AuditService.details("subuserId", subuserId, "booth", request.getBoothValue(), "slot", request.getSlot()), null);

        return mapToResponse(assignment);
    }

    @Override
    @Transactional
    public BoothAssignmentResponse revokeBoothAssignment(Long callerUserId, Role callerRole, Long customerAccountId, Long assignmentId, RevokeBoothAssignmentRequest request) {
        if (callerRole != Role.OWNER && callerRole != Role.CUSTOMER) {
            throw new ForbiddenException("Only Customer or Owner support can revoke booth assignments");
        }

        BoothAssignment assignment = boothAssignmentRepository.findById(assignmentId)
                .orElseThrow(() -> new ResourceNotFoundException("Booth assignment not found"));

        if (callerRole == Role.CUSTOMER && !assignment.getCustomerAccountId().equals(customerAccountId)) {
            throw new ResourceNotFoundException("Booth assignment not found");
        }

        if (assignment.getStatus() == BoothAssignmentStatus.REVOKED) {
            throw new BadRequestException("Booth assignment is already revoked");
        }

        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        assignment.setStatus(BoothAssignmentStatus.REVOKED);
        assignment.setRevokedAt(now);
        assignment.setRevokeReason(request.getRevokeReason());
        assignment.setRevokedByUserId(callerUserId);
        assignment = boothAssignmentRepository.save(assignment);

        // Cascade-revoke any active offline leases associated with this booth assignment
        jdbcTemplate.update("UPDATE offline_lease SET revoked_at = ?, revoked_by_user_id = ? WHERE booth_assignment_id = ? AND revoked_at IS NULL",
                java.sql.Timestamp.from(now), callerUserId, assignment.getId());

        auditService.logDetails(callerUserId, assignment.getCustomerAccountId(), null, null,
                "BOOTH_ASSIGNMENT_REVOKED", AuditOutcome.SUCCESS, "booth_assignment", assignment.getId().toString(),
                AuditService.details("reason", request.getRevokeReason()), null);

        return mapToResponse(assignment);
    }

    @Override
    @Transactional(readOnly = true)
    public List<SubuserDetailResponse> getSubusers(Long callerUserId, Role callerRole, Long customerAccountId) {
        if (callerRole != Role.CUSTOMER && callerRole != Role.OWNER) {
            throw new ForbiddenException("Access denied to subuser list");
        }

        Instant now = clock.instant();
        List<AppUser> subusers = userRepository.findByCustomerAccountId(customerAccountId).stream()
                .filter(u -> u.getRole() == Role.SUBUSER)
                .collect(Collectors.toList());

        return subusers.stream().map(u -> {
            List<BoothAssignmentResponse> assignments = boothAssignmentRepository
                    .findBySubuserIdAndStatus(u.getId(), BoothAssignmentStatus.ACTIVE).stream()
                    .filter(ba -> ba.getValidUntil().isAfter(now))
                    .map(this::mapToResponse)
                    .collect(Collectors.toList());

            return SubuserDetailResponse.builder()
                    .id(u.getId())
                    .username(u.getUsername())
                    .fullName(u.getFullName())
                    .role(u.getRole())
                    .customerAccountId(customerAccountId)
                    .status(u.getStatus())
                    .activeBoothAssignments(assignments)
                    .createdAt(u.getCreatedAt())
                    .build();
        }).collect(Collectors.toList());
    }

    @Override
    @Transactional(readOnly = true)
    public SubuserDetailResponse getSubuserDetail(Long callerUserId, Role callerRole, Long customerAccountId, Long subuserId) {
        AppUser subuser = userRepository.findById(subuserId)
                .orElseThrow(() -> new ResourceNotFoundException("Subuser not found"));

        if (!customerAccountId.equals(subuser.getCustomerAccountId())) {
            throw new ResourceNotFoundException("Subuser not found");
        }

        Instant now = clock.instant();
        List<BoothAssignmentResponse> assignments = boothAssignmentRepository
                .findBySubuserIdAndStatus(subuser.getId(), BoothAssignmentStatus.ACTIVE).stream()
                .filter(ba -> ba.getValidUntil().isAfter(now))
                .map(this::mapToResponse)
                .collect(Collectors.toList());

        return SubuserDetailResponse.builder()
                .id(subuser.getId())
                .username(subuser.getUsername())
                .fullName(subuser.getFullName())
                .role(subuser.getRole())
                .customerAccountId(customerAccountId)
                .status(subuser.getStatus())
                .activeBoothAssignments(assignments)
                .createdAt(subuser.getCreatedAt())
                .build();
    }

    private BoothAssignmentResponse mapToResponse(BoothAssignment ba) {
        return BoothAssignmentResponse.builder()
                .id(ba.getId())
                .customerAccountId(ba.getCustomerAccountId())
                .subuserId(ba.getSubuserId())
                .entitlementId(ba.getEntitlementId())
                .boothValue(ba.getBoothValue())
                .slot(ba.getSlot())
                .status(ba.getStatus())
                .validFrom(ba.getValidFrom())
                .validUntil(ba.getValidUntil())
                .assignedByUserId(ba.getAssignedByUserId())
                .createdAt(ba.getCreatedAt())
                .build();
    }
}
