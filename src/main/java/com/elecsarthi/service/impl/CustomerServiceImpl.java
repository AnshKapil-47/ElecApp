package com.elecsarthi.service.impl;

import com.elecsarthi.config.AppProperties;
import com.elecsarthi.dto.request.ProvisionCustomerRequest;
import com.elecsarthi.dto.response.CustomerDetailResponse;
import com.elecsarthi.dto.response.CustomerProvisionResponse;
import com.elecsarthi.dto.response.CustomerSummaryResponse;
import com.elecsarthi.dto.response.ReissueCodeResponse;
import com.elecsarthi.entity.AppUser;
import com.elecsarthi.entity.CustomerAccount;
import com.elecsarthi.entity.UserSession;
import com.elecsarthi.enums.ActivationStatus;
import com.elecsarthi.enums.AuditOutcome;
import com.elecsarthi.enums.Role;
import com.elecsarthi.enums.SessionStatus;
import com.elecsarthi.enums.UserStatus;
import com.elecsarthi.exception.common.BadRequestException;
import com.elecsarthi.exception.common.ConflictException;
import com.elecsarthi.exception.common.ForbiddenException;
import com.elecsarthi.exception.common.ResourceNotFoundException;
import com.elecsarthi.exception.errorcode.ErrorCode;
import com.elecsarthi.mapper.CustomerMapper;
import com.elecsarthi.repository.AppUserRepository;
import com.elecsarthi.repository.CustomerAccountRepository;
import com.elecsarthi.repository.UserSessionRepository;
import com.elecsarthi.security.device.CryptoUtils;
import com.elecsarthi.service.AuditService;
import com.elecsarthi.service.CustomerService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

@Service
@RequiredArgsConstructor
public class CustomerServiceImpl implements CustomerService {

    private final CustomerAccountRepository customerAccountRepository;
    private final AppUserRepository userRepository;
    private final UserSessionRepository sessionRepository;
    private final AuditService auditService;
    private final CustomerMapper customerMapper;
    private final AppProperties appProperties;
    private final Clock clock;
    private final JdbcTemplate jdbcTemplate;

    private static final String CROCKFORD_CHARS = "0123456789ABCDEFGHJKMNPQRSTVWXYZ";
    private final SecureRandom secureRandom = new SecureRandom();

    @Override
    @Transactional
    public CustomerProvisionResponse provisionCustomer(ProvisionCustomerRequest request, Long callerUserId, Role callerRole) {
        String phone = request.getContactPhone().trim();

        if (customerAccountRepository.existsByContactPhone(phone)) {
            throw new ConflictException(ErrorCode.CONFLICT, "A customer account with contact phone " + phone + " already exists.");
        }

        // Determine owning agent
        Long owningAgentUserId = null;
        if (callerRole == Role.AGENT) {
            owningAgentUserId = callerUserId;
        } else if (callerRole == Role.OWNER || callerRole == Role.SUBOWNER) {
            if (request.getOwningAgentUserId() != null) {
                AppUser agent = userRepository.findById(request.getOwningAgentUserId())
                        .orElseThrow(() -> new BadRequestException("Owning agent not found: " + request.getOwningAgentUserId()));
                if (agent.getRole() != Role.AGENT || agent.getStatus() != UserStatus.ACTIVE) {
                    throw new BadRequestException("Owning agent must be an active AGENT user.");
                }
                owningAgentUserId = agent.getId();
            }
        }

        // Generate Crockford Base32 10-char activation code
        String activationCode = generateCrockfordCode(10);
        String pepper = appProperties.getSecurity().getActivationPepper();
        String codeHash = CryptoUtils.hmacSha256Hex(activationCode, pepper);

        Instant now = clock.instant();
        Instant expiresAt = now.plus(appProperties.getSecurity().getActivationCodeExpirationHours(), ChronoUnit.HOURS);

        CustomerAccount customer = CustomerAccount.builder()
                .customerName(request.getCustomerName().trim())
                .partyOrOrgName(request.getPartyOrOrgName() != null ? request.getPartyOrOrgName().trim() : null)
                .contactPhone(phone)
                .contactEmail(request.getContactEmail() != null ? request.getContactEmail().trim().toLowerCase() : null)
                .status(UserStatus.ACTIVE)
                .activationStatus(ActivationStatus.PENDING)
                .activationCodeHash(codeHash)
                .activationCodeKeyVersion((short) 1)
                .activationCodeIssuedAt(now)
                .activationCodeExpiresAt(expiresAt)
                .activationCodeIssuedByUserId(callerUserId)
                .activationAttemptCount((short) 0)
                .createdByUserId(callerUserId)
                .owningAgentUserId(owningAgentUserId)
                .mobileAccessPolicyVersion(1)
                .createdAt(now)
                .build();

        customer = customerAccountRepository.save(customer);

        // Create primary customer app_user in PENDING status
        AppUser customerUser = AppUser.builder()
                .customerAccountId(customer.getId())
                .username(phone)
                .fullName(request.getCustomerName().trim())
                .role(Role.CUSTOMER)
                .status(UserStatus.PENDING)
                .createdByUserId(callerUserId)
                .createdAt(now)
                .updatedAt(now)
                .build();

        userRepository.save(customerUser);

        auditService.log(callerUserId, customer.getId(), null, null, "CUSTOMER_PROVISION", AuditOutcome.SUCCESS,
                "customer_account", customer.getId().toString(), "{\"phone\":\"" + phone + "\"}", null);

        return CustomerProvisionResponse.builder()
                .id(customer.getId())
                .customerName(customer.getCustomerName())
                .contactPhone(phone)
                .activationStatus(ActivationStatus.PENDING)
                .activationCode(activationCode)
                .activationCodeExpiresAt(expiresAt)
                .build();
    }

    @Override
    @Transactional
    public ReissueCodeResponse reissueActivationCode(Long customerId, Long callerUserId, Role callerRole) {
        CustomerAccount customer = getCustomerScoped(customerId, callerUserId, callerRole);

        if (customer.getActivationStatus() == ActivationStatus.ACTIVATED) {
            throw new BadRequestException("Account has already been activated.");
        }

        String newCode = generateCrockfordCode(10);
        String pepper = appProperties.getSecurity().getActivationPepper();
        String codeHash = CryptoUtils.hmacSha256Hex(newCode, pepper);

        Instant now = clock.instant();
        Instant expiresAt = now.plus(appProperties.getSecurity().getActivationCodeExpirationHours(), ChronoUnit.HOURS);

        customer.setActivationCodeHash(codeHash);
        customer.setActivationCodeKeyVersion((short) 1);
        customer.setActivationCodeIssuedAt(now);
        customer.setActivationCodeExpiresAt(expiresAt);
        customer.setActivationCodeIssuedByUserId(callerUserId);
        customer.setActivationAttemptCount((short) 0);

        customerAccountRepository.save(customer);

        auditService.log(callerUserId, customer.getId(), null, null, "ACTIVATION_CODE_REISSUE", AuditOutcome.SUCCESS,
                "customer_account", customer.getId().toString(), null, null);

        return ReissueCodeResponse.builder()
                .customerId(customer.getId())
                .contactPhone(maskPhone(customer.getContactPhone(), callerRole))
                .activationCode(newCode)
                .expiresAt(expiresAt)
                .build();
    }

    @Override
    @Transactional
    public void suspendCustomer(Long customerId, String reason, Long callerUserId, Role callerRole) {
        if (callerRole != Role.OWNER && callerRole != Role.SUBOWNER) {
            throw new ForbiddenException("Only Owner or Subowner can suspend customer accounts.");
        }

        CustomerAccount customer = customerAccountRepository.findById(customerId)
                .orElseThrow(() -> new ResourceNotFoundException("Customer not found: " + customerId));

        Instant now = clock.instant();
        customer.setStatus(UserStatus.SUSPENDED);
        customer.setSuspensionReason(reason != null ? reason.trim() : "ADMIN_SUSPENDED");
        customer.setSuspendedByUserId(callerUserId);
        customer.setSuspendedAt(now);
        customer.setMobileAccessPolicyVersion(customer.getMobileAccessPolicyVersion() + 1);

        customerAccountRepository.save(customer);

        // Terminate all sessions for primary user and all subusers under this customer
        List<AppUser> users = userRepository.findByCustomerAccountId(customerId);
        for (AppUser u : users) {
            List<UserSession> sessions = sessionRepository.findByUserIdAndStatus(u.getId(), SessionStatus.ACTIVE);
            for (UserSession s : sessions) {
                s.setStatus(SessionStatus.TERMINATED);
                s.setTerminatedAt(now);
                s.setTerminationReason("CUSTOMER_SUSPENDED");
                s.setTerminatedByUserId(callerUserId);
                sessionRepository.save(s);
            }
        }

        // Cascade-revoke all active offline leases for this customer
        jdbcTemplate.update(
                "UPDATE offline_lease SET revoked_at = ?, revoked_by_user_id = ? " +
                "WHERE booth_assignment_id IN (SELECT id FROM booth_assignment WHERE customer_account_id = ?) AND revoked_at IS NULL",
                java.sql.Timestamp.from(now), callerUserId, customerId);

        auditService.log(callerUserId, customer.getId(), null, null, "CUSTOMER_SUSPEND", AuditOutcome.SUCCESS,
                "customer_account", customer.getId().toString(), "{\"reason\":\"" + reason + "\"}", null);
    }

    @Override
    @Transactional
    public void reactivateCustomer(Long customerId, Long callerUserId, Role callerRole) {
        if (callerRole != Role.OWNER && callerRole != Role.SUBOWNER) {
            throw new ForbiddenException("Only Owner or Subowner can reactivate customer accounts.");
        }

        CustomerAccount customer = customerAccountRepository.findById(customerId)
                .orElseThrow(() -> new ResourceNotFoundException("Customer not found: " + customerId));

        customer.setStatus(UserStatus.ACTIVE);
        customer.setSuspensionReason(null);
        customer.setSuspendedByUserId(null);
        customer.setSuspendedAt(null);
        customer.setMobileAccessPolicyVersion(customer.getMobileAccessPolicyVersion() + 1);

        customerAccountRepository.save(customer);

        auditService.log(callerUserId, customer.getId(), null, null, "CUSTOMER_REACTIVATE", AuditOutcome.SUCCESS,
                "customer_account", customer.getId().toString(), null, null);
    }

    @Override
    @Transactional
    public void reassignAgent(Long customerId, Long newAgentId, Long callerUserId) {
        CustomerAccount customer = customerAccountRepository.findById(customerId)
                .orElseThrow(() -> new ResourceNotFoundException("Customer not found: " + customerId));

        AppUser newAgent = userRepository.findById(newAgentId)
                .orElseThrow(() -> new BadRequestException("New owning agent user not found: " + newAgentId));

        if (newAgent.getRole() != Role.AGENT || newAgent.getStatus() != UserStatus.ACTIVE) {
            throw new BadRequestException("Owning agent must be an active AGENT user.");
        }

        Long oldAgentId = customer.getOwningAgentUserId();
        customer.setOwningAgentUserId(newAgent.getId());
        customerAccountRepository.save(customer);

        auditService.log(callerUserId, customer.getId(), null, null, "CUSTOMER_AGENT_REASSIGN", AuditOutcome.SUCCESS,
                "customer_account", customer.getId().toString(), "{\"oldAgentId\":" + oldAgentId + ",\"newAgentId\":" + newAgentId + "}", null);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<CustomerSummaryResponse> listCustomers(Pageable pageable, Long callerUserId, Role callerRole) {
        Page<CustomerAccount> page;
        if (callerRole == Role.AGENT) {
            page = customerAccountRepository.findByOwningAgentUserId(callerUserId, pageable);
        } else {
            page = customerAccountRepository.findAll(pageable);
        }

        return page.map(c -> mapToSummary(c, callerRole));
    }

    @Override
    @Transactional(readOnly = true)
    public CustomerDetailResponse getCustomer(Long customerId, Long callerUserId, Role callerRole) {
        CustomerAccount customer = getCustomerScoped(customerId, callerUserId, callerRole);
        return mapToDetail(customer, callerRole);
    }

    private CustomerAccount getCustomerScoped(Long customerId, Long callerUserId, Role callerRole) {
        CustomerAccount customer = customerAccountRepository.findById(customerId)
                .orElseThrow(() -> new ResourceNotFoundException("Customer not found."));

        if (callerRole == Role.AGENT) {
            if (customer.getOwningAgentUserId() == null || !customer.getOwningAgentUserId().equals(callerUserId)) {
                // Return 404 so existence is not leaked to unauthorized agents
                throw new ResourceNotFoundException("Customer not found.");
            }
        }
        return customer;
    }

    private String generateCrockfordCode(int length) {
        StringBuilder sb = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            sb.append(CROCKFORD_CHARS.charAt(secureRandom.nextInt(CROCKFORD_CHARS.length())));
        }
        return sb.toString();
    }

    private String maskPhone(String phone, Role callerRole) {
        if (phone == null) return null;
        if (callerRole == Role.SUBOWNER) {
            if (phone.length() > 6) {
                return phone.substring(0, 3) + "*****" + phone.substring(phone.length() - 3);
            }
            return "*****";
        }
        return phone;
    }

    private CustomerSummaryResponse mapToSummary(CustomerAccount c, Role callerRole) {
        String agentName = null;
        if (c.getOwningAgentUserId() != null) {
            agentName = userRepository.findById(c.getOwningAgentUserId())
                    .map(AppUser::getFullName)
                    .orElse(null);
        }

        return customerMapper.toSummaryResponse(c, agentName, maskPhone(c.getContactPhone(), callerRole));
    }

    private CustomerDetailResponse mapToDetail(CustomerAccount c, Role callerRole) {
        String agentName = null;
        if (c.getOwningAgentUserId() != null) {
            agentName = userRepository.findById(c.getOwningAgentUserId())
                    .map(AppUser::getFullName)
                    .orElse(null);
        }

        return customerMapper.toDetailResponse(c, agentName, maskPhone(c.getContactPhone(), callerRole));
    }
}
