# ElecSarthi — Implementation Architecture

**Architecture Standard:** Centralized Layered Architecture  
**Base Package:** `com.elecsarthi`  
**Target Environment:** Java 25 LTS, Spring Boot 4.2.0, PostgreSQL 16+/18  
**Scope:** Backend Only

---

## 1. Architectural Philosophy

ElecSarthi follows a strict, non-negotiable **centralized, layered Spring Boot architecture**.
- **No feature-based package trees** (e.g., `owner/controller`, `sales/service`, `voter/repository` are prohibited).
- **No role-specific package trees** (e.g., `customer/`, `agent/`, `subuser/` are prohibited).
- **No microservices** or separate backend submodules.
- **One Maven project** at the repository root.
- **Package by technical layer**: Classes reside in packages defined by their architectural role, not their domain feature or calling user role. Role-based access control is handled through Spring Security and method authorization, not package placement.

---

## 2. Mandatory Package Structure

```text
com.elecsarthi
├── ElecSarthiApplication.java
│
├── controller/                 # REST Controllers (thin layer)
│   ├── AuthController.java
│   ├── OwnerController.java
│   ├── SubownerController.java
│   ├── AgentController.java
│   ├── CustomerController.java
│   ├── SubuserController.java
│   ├── DatasetController.java
│   ├── ElectionTypeController.java
│   ├── SaleController.java
│   ├── EntitlementController.java
│   ├── VoterController.java
│   ├── BoothController.java
│   ├── TurnoutController.java
│   ├── DeviceController.java
│   └── SyncController.java
│
├── service/                    # Business Service Interfaces
│   ├── AuthService.java
│   ├── OwnerService.java
│   ├── SubownerService.java
│   ├── AgentService.java
│   ├── CustomerService.java
│   ├── DatasetService.java
│   ├── DatasetImportService.java
│   ├── ElectionTypeService.java
│   ├── SaleService.java
│   ├── EntitlementService.java
│   ├── VoterService.java
│   ├── BoothService.java
│   ├── TurnoutService.java
│   ├── DeviceService.java
│   ├── SyncService.java
│   ├── AuditService.java
│   │
│   └── impl/                   # Business Service Implementations
│       ├── AuthServiceImpl.java
│       ├── OwnerServiceImpl.java
│       ├── SubownerServiceImpl.java
│       ├── AgentServiceImpl.java
│       ├── CustomerServiceImpl.java
│       ├── DatasetServiceImpl.java
│       ├── DatasetImportServiceImpl.java
│       ├── ElectionTypeServiceImpl.java
│       ├── SaleServiceImpl.java
│       ├── EntitlementServiceImpl.java
│       ├── VoterServiceImpl.java
│       ├── BoothServiceImpl.java
│       ├── TurnoutServiceImpl.java
│       ├── DeviceServiceImpl.java
│       ├── SyncServiceImpl.java
│       └── AuditServiceImpl.java
│
├── repository/                 # Spring Data JPA & Tenant Repositories
│   ├── CustomerAccountRepository.java
│   ├── AppUserRepository.java
│   ├── UserDeviceRepository.java
│   ├── UserSessionRepository.java
│   ├── AuditLogRepository.java
│   ├── ElectionTypeRepository.java
│   ├── ElectionTypeFieldRepository.java
│   ├── DatasetRepository.java
│   ├── ImportIssueRepository.java
│   ├── VoterRepository.java
│   ├── VoterTurnoutRepository.java
│   ├── AgentSaleRepository.java
│   ├── AgentSaleLineRepository.java
│   ├── EntitlementRepository.java
│   ├── BoothAssignmentRepository.java
│   ├── OfflineLeaseRepository.java
│   ├── SyncSessionRepository.java
│   └── DeviceNonceRepository.java
│
├── entity/                     # JPA Entities (exactly matching 18 tables)
│   ├── CustomerAccount.java
│   ├── AppUser.java
│   ├── UserDevice.java
│   ├── UserSession.java
│   ├── AuditLog.java
│   ├── ElectionType.java
│   ├── ElectionTypeField.java
│   ├── Dataset.java
│   ├── ImportIssue.java
│   ├── Voter.java
│   ├── VoterTurnout.java
│   ├── AgentSale.java
│   ├── AgentSaleLine.java
│   ├── Entitlement.java
│   ├── BoothAssignment.java
│   ├── OfflineLease.java
│   ├── SyncSession.java
│   └── DeviceNonce.java
│
├── dto/                        # Data Transfer Objects
│   ├── request/                # Inbound request bodies
│   │   ├── LoginRequest.java
│   │   ├── RefreshTokenRequest.java
│   │   ├── DeviceRegisterRequest.java
│   │   ├── DeviceVerifyRequest.java
│   │   ├── CustomerCreateRequest.java
│   │   ├── CustomerActivateRequest.java
│   │   ├── SaleCreateRequest.java
│   │   ├── SaleLineRequest.java
│   │   ├── DirectEntitlementRequest.java
│   │   ├── VoterSearchRequest.java
│   │   ├── TurnoutMarkRequest.java
│   │   ├── BoothAssignRequest.java
│   │   └── ...
│   │
│   └── response/               # Outbound API responses
│       ├── LoginResponse.java
│       ├── TokenRefreshResponse.java
│       ├── CustomerResponse.java
│       ├── AgentResponse.java
│       ├── DatasetResponse.java
│       ├── SaleResponse.java
│       ├── EntitlementResponse.java
│       ├── VoterResponse.java
│       ├── VoterFamilyResponse.java
│       ├── VoterSlipResponse.java
│       ├── TurnoutStatsResponse.java
│       ├── BoothAssignmentResponse.java
│       ├── OfflineLeaseResponse.java
│       ├── SyncManifestResponse.java
│       └── ...
│
├── enums/                      # Business & Domain Enums
│   ├── Role.java               # OWNER, SUBOWNER, AGENT, CUSTOMER, SUBUSER
│   ├── UserStatus.java         # PENDING, ACTIVE, SUSPENDED, DEACTIVATED
│   ├── ClientType.java         # WEB, MOBILE
│   ├── SessionStatus.java      # ACTIVE, TERMINATED, EXPIRED
│   ├── DeviceStatus.java       # PENDING, ACTIVE, REVOKED, REPLACED
│   ├── DevicePlatform.java     # ANDROID, IOS
│   ├── DatasetTier.java        # MASTER, LONG_TERM
│   ├── DatasetStatus.java      # PENDING, IMPORTED, FAILED, SUPERSEDED, DELETING
│   ├── SaleStatus.java         # COMPLETED, VOIDED
│   ├── EntitlementStatus.java  # ACTIVE, REVOKED
│   ├── EntitlementSource.java  # SALE, DIRECT
│   ├── DurationUnit.java       # DAY, WEEK, MONTH
│   ├── TurnoutSource.java      # ONLINE, OFFLINE_LEASE, OFFLINE_SYNC
│   └── ...
│
├── mapper/                     # Entity to DTO mappers
│   ├── UserMapper.java
│   ├── CustomerMapper.java
│   ├── DatasetMapper.java
│   ├── SaleMapper.java
│   ├── EntitlementMapper.java
│   ├── VoterMapper.java
│   └── BoothMapper.java
│
├── security/                   # Security infrastructure
│   ├── config/                 # SecurityFilterChain, WebSecurityCustomizer
│   ├── filter/                 # JwtAuthenticationFilter, DeviceSignatureFilter
│   ├── jwt/                    # JwtTokenProvider, JwtProperties
│   ├── device/                 # DeviceSignatureVerifier, NonceValidator
│   ├── signing/                # Ed25519 signer for offline leases & manifests
│   ├── authorization/          # VoterAccessGate, ResourceSecurityEvaluator
│   └── service/                # UserDetailsServiceImpl, SessionValidationService
│
├── exception/                  # Error handling & error codes
│   ├── handler/                # GlobalExceptionHandler
│   ├── common/                 # BusinessException, ResourceNotFoundException, AccessDeniedException
│   └── errorcode/              # ErrorCode enum and API ErrorResponse
│
└── config/                     # Application configurations
    ├── AppProperties.java
    ├── ClockConfig.java        # Injectable Clock bean (Asia/Kolkata default)
    ├── OpenApiConfig.java
    └── WebConfig.java
```

---

## 3. Strict Layering & Separation Rules

1. **Controllers are Thin**:
   - Zero business calculations or DB transactions in controllers.
   - Extract user identity (`sid`, `userId`, `role`, `customerAccountId`) from Spring Security `SecurityContext`.
   - Call service interface methods.
   - Return response DTOs with standardized `ResponseEntity`.
2. **Services Contain Business Rules**:
   - Interfaces in `com.elecsarthi.service`.
   - Implementations in `com.elecsarthi.service.impl`.
   - Enforce resource-level tenant scoping, validity date checks against `Clock`, dataset confidentiality, criteria canonicalization, and idempotency.
3. **Repositories Handle Data Access**:
   - Located exclusively in `com.elecsarthi.repository`.
   - Tenant-scoped repository methods ensuring cross-tenant queries cannot leak or modify unauthorized data.
4. **DTOs Never Expose Internals**:
   - JPA entities are NEVER exposed directly in controller responses.
   - All dataset IDs, file names, internal hashes, and unencrypted mobile numbers are stripped/masked before reaching DTOs.
